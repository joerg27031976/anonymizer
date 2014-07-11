package de.hpi.bp2013n1.anonymizer;

/*
 * #%L
 * Anonymizer
 * %%
 * Copyright (C) 2013 - 2014 HPI Bachelor's Project N1 2013
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


import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Joiner;

import de.hpi.bp2013n1.anonymizer.util.SQLHelper;

public class RowRetainService {
	
	private static final String RETAIN_TABLE_SUFFIX = "_RETAINED";
	static Logger logger = Logger.getLogger(RowRetainService.class.getName());
	
	public static class InsertRetainMarkFailed extends Exception {

		public InsertRetainMarkFailed() {
			super();
		}

		public InsertRetainMarkFailed(String message, Throwable cause,
				boolean enableSuppression, boolean writableStackTrace) {
			super(message, cause, enableSuppression, writableStackTrace);
		}

		public InsertRetainMarkFailed(String message, Throwable cause) {
			super(message, cause);
		}

		public InsertRetainMarkFailed(String message) {
			super(message);
		}

		public InsertRetainMarkFailed(Throwable cause) {
			super(cause);
		}

		private static final long serialVersionUID = -9146664576691464182L;
		
	}
	
	private Connection originalDatabase;
	private Connection transformationDatabase;
	
	class PrimaryKey {
		List<String> columnNames;
		List<String> columnTypeNames;
		String keyName;
		
		public List<String> columnDefinitions() {
			ArrayList<String> definitions = new ArrayList<>(columnNames.size());
			for (int i = 0; i < columnNames.size(); i++)
				definitions.add(columnNames.get(i) + " " 
						+ columnTypeNames.get(i));
			return definitions;
		}
	}
	
	Map<String, PrimaryKey> cachedPrimaryKeys = new HashMap<>();
	Set<String> tablesWithRetainedRows = new HashSet<>();

	public RowRetainService(Connection originalDatabase,
			Connection transformationDatabase) {
		this.originalDatabase = originalDatabase;
		this.transformationDatabase = transformationDatabase;
		
		lookForRetainTables();
	}

	private void lookForRetainTables() {
		if (transformationDatabase == null)
			return; // for testing purposes
		try (ResultSet retainTables = transformationDatabase.getMetaData()
				.getTables(null, null, "%" + RETAIN_TABLE_SUFFIX, 
						new String[] { "TABLE" })) {
			while (retainTables.next()) {
				String retainTableName = retainTables.getString("TABLE_NAME");
				String sourceTableName = retainTableName.substring(0, 
						retainTableName.length() - RETAIN_TABLE_SUFFIX.length());
				tablesWithRetainedRows.add(
						qualifiedTableName(retainTables.getString("TABLE_SCHEM"), 
								sourceTableName));
			}
		} catch (SQLException e) {
			logger.log(Level.SEVERE, "Could not search for retain mark tables", 
					e);
		}
	}
	
	public void retainCurrentRow(String schema, String table, ResultSet row) 
			throws InsertRetainMarkFailed {
		try {
			PrimaryKey pk = getPrimaryKey(schema, table);
			if (!retainTableExistsFor(schema, table))
				createRetainTableFor(schema, table, pk);
			tablesWithRetainedRows.add(qualifiedTableName(schema, table));
			try (PreparedStatement insertStatement = prepareRetainInsert(schema, table, pk)) {
				int columnIndex = 1;
				for (String columnName : pk.columnNames) {
					insertStatement.setObject(columnIndex++, row.getObject(columnName));
				}
				insertStatement.addBatch();
				insertStatement.executeUpdate();
			}
		} catch (SQLException e) {
			throw new InsertRetainMarkFailed("Could not mark row in "
					+ schema + "." + table + " to be retained", e);
		}
	}

	private void createRetainTableFor(String schema, String table, PrimaryKey pk) 
			throws SQLException {
		if (!schemaExists(schema, transformationDatabase))
			SQLHelper.createSchema(schema, transformationDatabase);
		try (Statement createTable = transformationDatabase.createStatement()) {
			createTable.executeUpdate("CREATE TABLE " 
					+ retainTableName(schema, table)
					+ " (" + Joiner.on(',').join(pk.columnDefinitions()) + ", "
					+ "PRIMARY KEY (" + Joiner.on(',').join(pk.columnNames) + "))");
		}
	}

	private boolean schemaExists(String schema,
			Connection database) throws SQLException {
		try (ResultSet schemas = database.getMetaData().getSchemas(null, schema)) {
			return schemas.next();
		}
	}

	private boolean retainTableExistsFor(String schema, String table) 
			throws SQLException {
		try (ResultSet tables = transformationDatabase.getMetaData()
				.getTables(null, schema, table, new String[] { "TABLE" })) {
			return tables.next();
		}
	}

	private PreparedStatement prepareRetainInsert(String schema, String table,
			PrimaryKey pk) throws SQLException {
		return transformationDatabase.prepareStatement(retainInsertQuery(schema, table, pk));
	}

	String retainInsertQuery(String schema, String table, PrimaryKey pk) {
		Joiner commaJoiner = Joiner.on(',');
		List<Character> placeholders = new ArrayList<>(pk.columnNames.size());
		for (int i = 0; i < pk.columnNames.size(); i++)
			placeholders.add('?');
		return "INSERT INTO " + retainTableName(schema, table) + " (" 
			+ commaJoiner.join(pk.columnNames) + ") VALUES (" 
			+ commaJoiner.join(placeholders) + ")";
	}

	private String retainTableName(String schema, String table) {
		return schema + "." + table + RETAIN_TABLE_SUFFIX;
	}

	private PrimaryKey getPrimaryKey(String schema, String table) 
			throws SQLException {
		PrimaryKey pk = cachedPrimaryKeys.get(qualifiedTableName(schema, table));
		if (pk != null)
			return pk;
		DatabaseMetaData metaData = originalDatabase.getMetaData();
		pk = new PrimaryKey();
		try (ResultSet pkResultSet = metaData.getPrimaryKeys(null, schema, table)) {
			if (!pkResultSet.next()) {
				// TODO: no primary key
			}
			pk.keyName = pkResultSet.getString("PK_NAME");
			TreeMap<Integer, String> columns = new TreeMap<>();
			do {
				columns.put(pkResultSet.getInt("KEY_SEQ"), 
						pkResultSet.getString("COLUMN_NAME"));
			} while (pkResultSet.next());
			pk.columnNames = new ArrayList<>(columns.values());
			try (PreparedStatement select = originalDatabase.prepareStatement(
					"SELECT " + Joiner.on(',').join(pk.columnNames) 
					+ " FROM " + qualifiedTableName(schema, table)
					+ " WHERE 1 = 0")) { // only interested in metadata
				ResultSetMetaData selectPKMetaData = select.getMetaData();
				pk.columnTypeNames = new ArrayList<>(pk.columnNames.size());
				for (int i = 1; i <= pk.columnNames.size(); i++) {
					pk.columnTypeNames.add(selectPKMetaData.getColumnTypeName(i));
				}
			}
		}
		return pk;
	}

	private String qualifiedTableName(String schema, String table) {
		return schema + "." + table;
	}

	public boolean currentRowShouldBeRetained(String schema, String table,
			ResultSetRowReader row) throws SQLException {
		if (!tablesWithRetainedRows.contains(qualifiedTableName(schema, table)))
			return false;
		PrimaryKey primaryKey = getPrimaryKey(schema, table);
		Map<String, Object> comparisons = new TreeMap<>();
		for (String pkColumn : primaryKey.columnNames)
			comparisons.put(pkColumn + " = ?", row.getObject(pkColumn));
		try (PreparedStatement select = transformationDatabase.prepareStatement(
				selectRetainedPrimaryKeyQuery(schema, table, comparisons))) {
			int i = 1;
			for (Object value : comparisons.values()) {
				select.setObject(i++, value);
			}
			try (ResultSet result = select.executeQuery()) {
				return result.next();
			}
		}
	}

	String selectRetainedPrimaryKeyQuery(String schema, String table,
			Map<String, Object> comparisons) {
		return "SELECT 1 FROM " + retainTableName(schema, table) 
		+ " WHERE " + Joiner.on(" AND ").join(comparisons.keySet());
	}

}
