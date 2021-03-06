package de.hpi.bp2013n1.anonymizer.analyzer;

/*
 * #%L
 * Analyzer
 * %%
 * Copyright (C) 2013 - 2014 HPI-BP2013N1
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import de.hpi.bp2013n1.anonymizer.Anonymizer;
import de.hpi.bp2013n1.anonymizer.NoOperationStrategy;
import de.hpi.bp2013n1.anonymizer.RuleValidator;
import de.hpi.bp2013n1.anonymizer.TransformationStrategy;
import de.hpi.bp2013n1.anonymizer.db.TableField;
import de.hpi.bp2013n1.anonymizer.shared.Config;
import de.hpi.bp2013n1.anonymizer.shared.Rule;
import de.hpi.bp2013n1.anonymizer.shared.Scope;

public class Analyzer {
	public Connection connection;
	public Config config;
	public Scope scope;
	
	public static class FatalError extends Exception {
		private static final long serialVersionUID = -2480888204710117127L;

		public FatalError(String message, Throwable cause,
				boolean enableSuppression, boolean writableStackTrace) {
			super(message, cause, enableSuppression, writableStackTrace);
		}

		public FatalError(String message, Throwable cause) {
			super(message, cause);
		}

		public FatalError(String message) {
			super(message);
		}

		public FatalError(Throwable cause) {
			super(cause);
		}
		
	}
	
	static Logger logger = Logger.getLogger(Analyzer.class.getName());
	private Map<String, TransformationStrategy> strategies;
	private RuleValidator ruleValidator;
	private Multimap<TableField, Rule> rulesByTableField = ArrayListMultimap.create();
	
	public Analyzer(Connection con, Config config, Scope scope){
		this.connection = con;
		this.config = config;
		this.scope = scope;
	}
	

	public void run(String outputFilename) throws FatalError {
		logger.fine("Starting analysis at " + Calendar.getInstance().getTime());
		setUpTransformationStrategies();
		// read meta tables and find dependents
		try {
			System.out.println("> Processing Rules");
			validateRulesAndAddDependants();

			config.removeNoOpRulesWithoutDependants();
			System.out.println("> Writing output file");
			// write to intermediary config file
			writeNewConfigToFile(outputFilename);
			
			System.out.println("done");
			logger.fine("Finished writing config file at "
					+ Calendar.getInstance().getTime());
			
		} catch (SQLException e) {
			logger.severe("Database error: " + e.getMessage());
			throw new FatalError(e);
		} catch (IOException e) {
			logger.severe("Failed to write output file");
			throw new FatalError(e);
		}
	}


	void setUpTransformationStrategies() throws FatalError {
		strategies = loadAndInstanciateStrategies();
	}

	
	void validateRulesAndAddDependants() throws SQLException {
		DatabaseMetaData metaData = connection.getMetaData();
		addRulesForPrimaryKeyColumns(metaData);
		initializeRulesByTableField();
		findDependantsByForeignKeys(metaData);
		ruleValidator = new RuleValidator(metaData);
		Iterator<Rule> ruleIterator = config.rules.iterator();
		while (ruleIterator.hasNext()) {
			Rule rule = ruleIterator.next();
			System.out.println("  Processing: " + rule);
			if (!ruleValidator.isValid(rule)) {
				ruleIterator.remove();
				logger.warning("Skipping rule " + rule);
				continue;
			}
			validateExistingDependants(rule, metaData);
			findPossibleDependantsByName(rule, metaData);
		}
		config.removeNoOpRulesWithoutDependants();
	}

	void initializeRulesByTableField() {
		for (Rule rule : config.rules) {
			rulesByTableField.put(rule.getTableField(), rule);
		}
	}

	void addRulesForPrimaryKeyColumns(DatabaseMetaData metaData) throws SQLException {
		Multimap<String, Rule> rulesByParentTable = HashMultimap.create();
		for (Rule rule : config.rules) {
			rulesByParentTable.put(rule.getTableField().table, rule);
		}
		try (ResultSet tablesResultSet = metaData.getTables(
				null, config.schemaName, null, new String[] { "TABLE" })) {
			while (tablesResultSet.next()) {
				String tableName = tablesResultSet.getString("TABLE_NAME");
				Collection<Rule> rulesForTable = rulesByParentTable.get(tableName);
				try (ResultSet primaryKeyResultSet =
						metaData.getPrimaryKeys(null, config.schemaName, tableName)) {
					primaryKeyColumnsLoop:
					while (primaryKeyResultSet.next()) {
						TableField pkTableField = new TableField(
								tablesResultSet.getString("TABLE_NAME"),
								primaryKeyResultSet.getString("COLUMN_NAME"),
								tablesResultSet.getString("TABLE_SCHEM"));
						if (!scope.tables.contains(pkTableField.getTable()))
							continue;
						if (!pkTableField.getSchema().equals(config.getSchemaName()))
							continue;
						for (Rule ruleForTable : rulesForTable) {
							if (ruleForTable.getTableField().equals(pkTableField))
								continue primaryKeyColumnsLoop;
						}
						// no rule applies to this column yet
						config.addNoOpRuleFor(pkTableField);
					}
				}
			}
		}
	}


	/** Returns true if any rule in the config contains the argument as a dependent. */
	boolean anyRuleContainsDependent(TableField dependentTableField) {
		for (Rule anyRule : config.getRules()) {
			if (anyRule.getDependants().contains(dependentTableField))
				return true;
		}
		return false;
	}

	/**
	 * Returns true if for the suggested parent and suggested child attribute
	 * a Rule exists in which the suggested parent is a dependent of the
	 * suggested child.
	 * 
	 * @param suggestedParent
	 * @param suggestedChild
	 * @return
	 */
	boolean inverseRuleExists(TableField suggestedParent, TableField suggestedChild) {
		Collection<Rule> rulesForChild = rulesByTableField.get(suggestedChild);
		for (Rule anyRule : rulesForChild) {
			if (anyRule.getDependants().contains(suggestedParent))
				return true;
		}
		return false;
	}
	
	/**
	 * Add dependants and Rules for attributes which could be dependents on other
	 * attributes only judging by their name.
	 * 
	 * @param rule
	 * @param metaData
	 * @throws SQLException
	 */
	void findPossibleDependantsByName(Rule rule,
			DatabaseMetaData metaData) throws SQLException {
		TableField parentField = rule.getTableField();
		try (ResultSet similarlyNamedColumns = metaData.getColumns(null,
				config.getSchemaName(),
				null, "%" + parentField.getColumn() + "%")) {
			while (similarlyNamedColumns.next()) {
				if (!scope.tables.contains(
						similarlyNamedColumns.getString("TABLE_NAME")))
					continue;
				TableField newItem = new TableField(
						similarlyNamedColumns.getString("TABLE_NAME"),
						similarlyNamedColumns.getString("COLUMN_NAME"),
						similarlyNamedColumns.getString("TABLE_SCHEM"));
				if (!newItem.equals(parentField)
						&& !anyRuleContainsDependent(newItem)
						&& !inverseRuleExists(parentField, newItem))
					rule.addPotentialDependant(newItem);
			}
		}
	}

	
	void findDependantsByForeignKeys(DatabaseMetaData metaData) throws SQLException {
		// find all dependants by foreign keys
		for (String tableName : scope.tables) {
			try (ResultSet exportedKeys = metaData.getExportedKeys(
					null, config.getSchemaName(), tableName)) {
				addDependantsFromReferences(exportedKeys);
			}
			try (ResultSet importedKeys = metaData.getImportedKeys(
					null, config.getSchemaName(), tableName)) {
				addDependantsFromReferences(importedKeys);
			}
		}
	}


	private void addDependantsFromReferences(ResultSet referencesFromMetaData)
			throws SQLException {
		referencesLoop:
		while (referencesFromMetaData.next()) {
			if (!scope.tables.contains(referencesFromMetaData.getString("FKTABLE_NAME"))
					|| !scope.tables.contains(referencesFromMetaData.getString("PKTABLE_NAME")))
				continue;
			TableField pkTableField = new TableField(
					referencesFromMetaData.getString("PKTABLE_NAME"),
					referencesFromMetaData.getString("PKCOLUMN_NAME"),
					referencesFromMetaData.getString("PKTABLE_SCHEM"));
			if (!pkTableField.getSchema().equals(config.getSchemaName()))
				continue;
			Collection<Rule> rulesForPKColumn = rulesByTableField.get(pkTableField);
			if (rulesForPKColumn.isEmpty()) {
				rulesForPKColumn.add(config.addNoOpRuleFor(pkTableField));
			}
			TableField fkTableField = new TableField(
					referencesFromMetaData.getString("FKTABLE_NAME"),
					referencesFromMetaData.getString("FKCOLUMN_NAME"),
					referencesFromMetaData.getString("FKTABLE_SCHEM"));
			if (!fkTableField.getSchema().equals(config.getSchemaName()))
				continue;
			for (Rule ruleForPKColumn : rulesForPKColumn) {
				if (ruleForPKColumn.getDependants().contains(fkTableField))
					continue referencesLoop; // is already a dependent
			}
			rulesForPKColumn.iterator().next().addDependant(fkTableField);
		}
	}


	void validateExistingDependants(Rule rule, DatabaseMetaData metaData)
			throws SQLException {
		// verify predefined dependents
		Iterator<TableField> dependantIterator = rule.getDependants().iterator();
		while (dependantIterator.hasNext()) {
			TableField dependant = dependantIterator.next();
			if (!tableFieldExists(dependant, metaData)) {
				logger.severe("Dependant " + dependant + " does not exist in the schema. Skipping it.");
				dependantIterator.remove();
				continue;
			}
		}
	}
	
	boolean tableFieldExists(TableField tableField, DatabaseMetaData metaData)
			throws SQLException {
		try (ResultSet columns = metaData.getColumns(null,
				tableField.getSchema(),
				tableField.getTable(),
				tableField.getColumn())) {
			// there by definition is exactly one result set, or the field does not exist
			return columns.next();
		}
	}


	private Map<String, TransformationStrategy> loadAndInstanciateStrategies()
			throws FatalError {
		// needed to create TransformationStrategy objects
		Anonymizer stubAnonymizer = new Anonymizer(config, scope);
		try {
			return loadAndInstanciateStrategies(stubAnonymizer);
		} catch (ClassNotFoundException | NoSuchMethodException
				| SecurityException | InstantiationException
				| IllegalAccessException | IllegalArgumentException e) {
			logger.severe("Could not load or instanciate strategy: "
				+ e.getMessage());
			throw new FatalError(e);
		} catch (InvocationTargetException e) {
			logger.severe("Could not load or instanciate strategy: "
					+ e.getCause().getMessage());
			throw new FatalError(e);
		}
	}



	private Map<String, TransformationStrategy> loadAndInstanciateStrategies(
			Anonymizer stubAnonymizer) throws ClassNotFoundException,
			NoSuchMethodException, InstantiationException,
			IllegalAccessException, InvocationTargetException {
		Map<String, TransformationStrategy> strategies = new HashMap<>();
		for (Map.Entry<String, String> strategyClassEntry : config.strategyMapping
				.entrySet()) {
			String strategyClassName = strategyClassEntry.getValue();
			strategies.put(strategyClassEntry.getKey(), TransformationStrategy
					.loadAndCreate(strategyClassName, stubAnonymizer,
							connection, null));
		}
		strategies.put(Config.NO_OP_STRATEGY_KEY, TransformationStrategy.loadAndCreate(
				NoOperationStrategy.class.getName(),
				stubAnonymizer, connection, null));
		config.setRuleTransformations(strategies);
		return strategies;
	}

	
	void writeNewConfigToFile(String outputFilename) throws IOException {
		File file = new File(outputFilename);
		try (FileWriter fw = new FileWriter(file);
				BufferedWriter writer = new BufferedWriter(fw)) {
			config.writeTo(writer);
		}
	}
}
