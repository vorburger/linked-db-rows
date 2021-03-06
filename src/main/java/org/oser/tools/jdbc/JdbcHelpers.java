package org.oser.tools.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.oser.tools.jdbc.Fk.getFksOfTable;

public final class JdbcHelpers {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcHelpers.class);

    private JdbcHelpers() {}

    /** @see JdbcHelpers#determineOrder(Connection, String, boolean, Cache) */
    public static List<String> determineOrder(Connection connection, String rootTable, boolean exceptionWithCycles) throws SQLException {
        return determineOrder(connection, rootTable, exceptionWithCycles, Caffeine.newBuilder()
                .maximumSize(10_000).build());
    }

    /** If one would like to import the tree starting at rootTable, what order should one insert the tables?
     *  @return a List<String> with the table names in the order in which to insert them, the table names are converted to
     *   lower case
     *  CAVEAT: may return a partial list (in case there are cycles/ there is no layering in the table dependencies)
     *
     * @throws IllegalStateException if there is a cycle and exceptionWithCycles is true
     * @throws SQLException if there is an issue with SQL metadata queries <p>
     *
     *  todo: could we all separate non cyclic parts of the graph? Would that help?
     *  */
    public static List<String> determineOrder(Connection connection, String rootTable, boolean exceptionWithCycles, Cache<String, List<Fk>> cache) throws SQLException {
        Set<String> treated = new HashSet<>();

        Map<String, Set<String>> dependencyGraph = calculateDependencyGraph(rootTable, treated, connection, cache);
        List<String> orderedTables = new ArrayList<>();

        Set<String> stillToTreat = new HashSet<>(treated);
        while (!stillToTreat.isEmpty()) {
            // remove all for which we have a constraint
            Set<String> treatedThisTime = new HashSet<>(stillToTreat);
            treatedThisTime.removeAll(dependencyGraph.keySet());

            orderedTables.addAll(treatedThisTime);
            stillToTreat.removeAll(treatedThisTime);

            if (treatedThisTime.isEmpty()) {
                LOGGER.warn("Not a layered organization of table dependencies - excluding connected tables: {}", dependencyGraph);
                if (exceptionWithCycles)
                    throw new IllegalStateException("cyclic sql dependencies - aborting "+dependencyGraph);
                break; // returning a partial list
            }

            // remove the constraints that get eliminated by treating those
            dependencyGraph.keySet().forEach(key -> dependencyGraph.get(key).removeAll(treatedThisTime));
            dependencyGraph.entrySet().removeIf(e -> e.getValue().isEmpty());
        }

        return orderedTables;
    }

    /**
     *  Determine all "Y requires X" relationships between the tables of the db, starting from rootTable
     *  @param rootTable is the root table we start from <p>
     *  @param treated all tables followed<p>
     *  @param cache is the FK cache <p>
     *
     * @return constraints in the form Map<X, Y> :  X is used in all Y (X is the key, Y are the values (Y is a set of all values),
     * all tables are lower case
     */
    private static Map<String, Set<String>> calculateDependencyGraph(String rootTable, Set<String> treated, Connection connection, Cache<String, List<Fk>> cache) throws SQLException {
        rootTable = rootTable.toLowerCase();
        Set<String> tablesToTreat = new HashSet<>();
        tablesToTreat.add(rootTable);

        Map<String, Set<String>> dependencyGraph = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        while (!tablesToTreat.isEmpty()) {
            String next = tablesToTreat.iterator().next();
            tablesToTreat.remove(next);

            List<Fk> fks = getFksOfTable(connection, next, cache);
            for (Fk fk : fks) {
                String tableToAdd = fk.pktable.toLowerCase();
                String otherTable = fk.fktable.toLowerCase();

                addToTreat(tablesToTreat, treated, tableToAdd);
                addToTreat(tablesToTreat, treated, otherTable);

                addDependency(dependencyGraph, tableToAdd, otherTable);
            }

            treated.add(next);
        }
        return dependencyGraph;
    }

    private static void addToTreat(Set<String> tablesToTreat, Set<String> treated, String tableToAdd) {
        if (!treated.contains(tableToAdd)) {
            tablesToTreat.add(tableToAdd);
        }
    }

    private static void addDependency(Map<String, Set<String>> dependencyGraph, String lastTable, String tableToAdd) {
        if (!lastTable.equals(tableToAdd)) {
            dependencyGraph.putIfAbsent(tableToAdd, new HashSet<>());
            dependencyGraph.get(tableToAdd).add(lastTable);
        }
    }

    /**
     * generate insert or update statement to insert columnNames into tableName
     * // todo: this only works for updating with 1 primary key fields
     */
    public static String getSqlInsertOrUpdateStatement(String tableName, List<String> columnNames, String pkName, boolean isInsert, Map<String, ColumnMetadata> columnMetadata) {
        String result;
        String fieldList = columnNames.stream().filter(name -> (isInsert || !name.toLowerCase().equals(pkName.toLowerCase()))).collect(Collectors.joining(isInsert ? ", " : " = ?, "));

        if (isInsert) {
            Map<String, ColumnMetadata> metadataInCurrentTableAndInsert = columnMetadata.entrySet().stream().filter(e -> columnNames.contains(e.getKey().toLowerCase())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            String questionsMarks = metadataInCurrentTableAndInsert.values().stream().sorted(Comparator.comparing(ColumnMetadata::getOrdinalPos))
                    .map(JdbcHelpers::questionMarkOrTypeCasting).collect(Collectors.joining(", "));
            result = "insert into " + tableName + " (" + fieldList + ") values (" + questionsMarks + ")";
        } else {
            fieldList += " = ? ";

            result = "update " + tableName + " set " + fieldList + " where " + pkName + " = ?";
        }

        return result;
    }

    private static String questionMarkOrTypeCasting(ColumnMetadata e) {
        if (e != null && e.columnDef != null && e.columnDef.endsWith(e.type) &&
                // mysql puts CURRENT_TIMESTAMP as the columnDef of Timestamp, this leads to an automatically set fields
                !e.columnDef.equals("CURRENT_TIMESTAMP")){
            // to handle inserts e.g. for enums correctly
            return e.columnDef.replace("'G'", "?");
        }

        return "?";
    }

    public static void assertTableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData dbm = connection.getMetaData();

        try (ResultSet tables = dbm.getTables(null, null, adaptCaseForDb(tableName, dbm.getDatabaseProductName()), null)) {
            if (tables.next()) {
                return; // Table exists
            } else {
                throw new IllegalArgumentException("Table " + tableName + " does not exist");
            }
        }
    }

    static String adaptCaseForDb(String originalName, String dbProductName) {
        switch (dbProductName) {
            case "PostgreSQL":
                return originalName.toLowerCase();
            case "H2":
                return originalName.toUpperCase();
            case "MySQL":
                return originalName;
            default:
                return originalName.toUpperCase();
        }
    }

    public static SortedMap<String, ColumnMetadata> getColumnMetadata(DatabaseMetaData metadata, String tableName, Cache<String, SortedMap<String, ColumnMetadata>> cache) throws SQLException {
        SortedMap<String, ColumnMetadata> result = cache.getIfPresent(tableName);
        if (result == null){
            result = getColumnMetadata(metadata, tableName);
        }
        cache.put(tableName, result);
        return result;
    }

    /**
     * @return Map à la fieldName1 -> ColumnMetadata (simplified JDBC metadata)
     */
    public static SortedMap<String, ColumnMetadata> getColumnMetadata(DatabaseMetaData metadata, String tableName) throws SQLException {
        SortedMap<String, ColumnMetadata> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        try (ResultSet rs = metadata.getColumns(null, null, adaptCaseForDb(tableName, metadata.getDatabaseProductName()), null)) {

            while (rs.next()) {
                String column_name = rs.getString("COLUMN_NAME").toLowerCase();
                result.put(column_name,
                        new ColumnMetadata(column_name,
                                rs.getString("TYPE_NAME"),
                                rs.getInt("DATA_TYPE"),
                                rs.getInt("SOURCE_DATA_TYPE"),
                                rs.getString("COLUMN_SIZE"),
                                rs.getString("COLUMN_DEF"),
                                rs.getInt("ORDINAL_POSITION")));

                // todo rm again
//            ResultSetMetaData rsMetaData = rs.getMetaData();
//            for (int i = 1; i<= rsMetaData.getColumnCount() ; i++){
//                System.out.println(rsMetaData.getColumnName(i)+" "+rs.getObject(i));
//                if (rsMetaData.getColumnName(i).equals("COLUMN_DEF") && rs.getObject(i) != null){
//                    System.out.println(rs.getObject(i).getClass());
//                }
//            }
//            System.out.println();
            }


            return result;
        }
    }

    /** @see #getPrimaryKeys(DatabaseMetaData, String) with optional caching */
    public static List<String> getPrimaryKeys(DatabaseMetaData metadata, String tableName, Cache<String, List<String>> cache) throws SQLException {
        List<String> result = cache.getIfPresent(tableName);
        if (result == null){
            result = getPrimaryKeys(metadata, tableName);
        }
        cache.put(tableName, result);
        return result;
    }

    /** Get the list of primary keys of a table */
    public static List<String> getPrimaryKeys(DatabaseMetaData metadata, String tableName) throws SQLException {
        List<String> result = new ArrayList<>();

        try (ResultSet rs = metadata.getPrimaryKeys(null, null, adaptCaseForDb(tableName, metadata.getDatabaseProductName()))) {
            while (rs.next()) {
                result.add(rs.getString("COLUMN_NAME"));
            }
            return result;
        }
    }


    /**
     * Set a value on a jdbc Statement
     *
     *   for cases where we have less info, columnMetadata can be null
     */
    // todo: one could use the int type info form the metadata
    // todo: clean up arguments (redundant)
    public static void innerSetStatementField(PreparedStatement preparedStatement, String typeAsString, int statementIndex, String valueToInsert, ColumnMetadata columnMetadata) throws SQLException {
        boolean isEmpty = valueToInsert == null || (valueToInsert.trim().isEmpty() || valueToInsert.equals("null"));
        switch (typeAsString.toUpperCase()) {
            case "BOOLEAN":
            case "BOOL":
                if (isEmpty) {
                    preparedStatement.setNull(statementIndex, Types.BOOLEAN);
                } else {
                    preparedStatement.setBoolean(statementIndex, Boolean.parseBoolean(valueToInsert.trim()));
                }
                break;
            case "SERIAL":
            case "INT":
            case "INT2":
            case "INT4":
            case "INTEGER":
            case "NUMBER":
            case "INT8":
            case "FLOAT4":
            case "FLOAT8":
                if (isEmpty) {
                    preparedStatement.setNull(statementIndex, Types.NUMERIC);
                } else {
                    preparedStatement.setLong(statementIndex, Long.parseLong(valueToInsert.trim()));
                }
                break;
            case "NUMERIC":
            case "DECIMAL":
                if (isEmpty) {
                    preparedStatement.setNull(statementIndex, Types.NUMERIC);
                } else {
                    preparedStatement.setDouble(statementIndex, Double.parseDouble(valueToInsert.trim()));
                }
                break;
            case "DATE":
            case "TIMESTAMP":
                if (isEmpty) {
                    preparedStatement.setNull(statementIndex, Types.TIMESTAMP);
                } else {
                    if (typeAsString.toUpperCase().equals("TIMESTAMP")) {
                        LocalDateTime localDateTime = LocalDateTime.parse(valueToInsert.replace(" ", "T"));
                        preparedStatement.setTimestamp(statementIndex, Timestamp.valueOf(localDateTime));
                    } else {
                        LocalDate localDate = LocalDate.parse(valueToInsert.replace(" ", "T"));
                        preparedStatement.setDate(statementIndex, Date.valueOf(String.valueOf(localDate)));
                    }
                }
                break;
            default:
                if (columnMetadata != null && columnMetadata.getDataType() != Types.ARRAY ) {
                    if (valueToInsert == null){
                        preparedStatement.setNull(statementIndex, columnMetadata.getDataType());
                    } else {
                        preparedStatement.setObject(statementIndex, valueToInsert, columnMetadata.dataType);
                    }
                } else {
                    // todo: do we need more null handling? (but we lack the int dataType)
                    preparedStatement.setObject(statementIndex, valueToInsert);
                }
        }
    }

    /**
     * represents simplified JDBC metadata
     */
    public static class ColumnMetadata {
        String name;
        String type;
        /** {@link java.sql.Types} */
        private final int dataType; //
        /** source type of a distinct type or user-generated Ref type, SQL type from java.sql.Types
         * (<code>null</code> if DATA_TYPE  isn't DISTINCT or user-generated REF) */
        private final int sourceDataType;

        String size; // adapt later?
        private final String columnDef;
        // starts at 1
        private final int ordinalPos;

        public ColumnMetadata(String name, String type, int dataType, int sourceDataType, String size, String columnDef, int ordinalPos) {
            this.name = name;
            this.type = type;
            this.dataType = dataType;
            this.sourceDataType = sourceDataType;
            this.size = size;
            this.columnDef = columnDef;
            this.ordinalPos = ordinalPos;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getSize() {
            return size;
        }

        public int getOrdinalPos() {
            return ordinalPos;
        }

        public int getDataType() {
            return dataType;
        }
    }

    /** get Map with keys = the field names and value = index of the key (0 started) */
    public static Map<String, Integer> getStringIntegerMap(List<String> primaryKeys) {
        final int[] j = {0};
        return primaryKeys.stream().collect(Collectors.toMap(e -> e.toLowerCase(), e -> j[0]++));
    }

    /** Does the row of the table tableName and primary key pkNames and the pkValues exist? */
    public static boolean doesPkTableExist(Connection connection, String tableName, List<String> pkNames,
                                           List<Object> pkValues, Map<String, JdbcHelpers.ColumnMetadata> columnMetadata) throws SQLException {
        String selectStatement = selectStatementByPks(tableName, pkNames, columnMetadata);

        boolean exists = false;
        try (PreparedStatement pkSelectionStatement = connection.prepareStatement(selectStatement)) {
            setPksStatementFields(pkSelectionStatement, pkNames, columnMetadata, pkValues);
            try (ResultSet rs = pkSelectionStatement.executeQuery()) {
                exists = rs.next();
            }
        }
        return exists;
    }

    private static String selectStatementByPks(String tableName, List<String> primaryKeys, Map<String, JdbcHelpers.ColumnMetadata> columnMetadata) {
        String whereClause = primaryKeys.stream().map(e -> e + " = " + questionMarkOrTypeCasting(columnMetadata.get(e.toLowerCase())))
                .collect(Collectors.joining(" and "));
        return "SELECT * from " + tableName + " where  " + whereClause;
    }

    private static void setPksStatementFields(PreparedStatement pkSelectionStatement, List<String> primaryKeys, Map<String, JdbcHelpers.ColumnMetadata> columnMetadata, List<Object> values) throws SQLException {
        int i = 0;
        for (String pkName : primaryKeys) {
            JdbcHelpers.ColumnMetadata fieldMetadata = columnMetadata.get(pkName.toLowerCase());
            if (fieldMetadata == null) {
                throw new IllegalArgumentException("Issue with metadata " + columnMetadata);
            }
            JdbcHelpers.innerSetStatementField(pkSelectionStatement, fieldMetadata.getType(), i + 1, Objects.toString(values.get(i)), fieldMetadata);
            i++;
        }
    }

    /** not yet very optimized */
    public static Map<String, Integer> getNumberElementsInEachTable(Connection connection) throws SQLException {
        Map<String, Integer> result = new HashMap<>();

        for (String tableName : getAllTableNames(connection)) {
            Statement statement = connection.createStatement();
            try (ResultSet resultSet = statement.executeQuery("select count(*) from \"" + tableName + "\"")) {

                while (resultSet.next()) {
                    result.put(tableName, resultSet.getInt(1));
                }
            }
        }

        return result;
    }

    public static List<String> getAllTableNames(Connection connection) throws SQLException {
        List<String> tableNames = new ArrayList<>();

        DatabaseMetaData md = connection.getMetaData();
        try (ResultSet rs = md.getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tableNames.add(rs.getString("TABLE_NAME"));
            }
        }
        return tableNames;
    }

}
