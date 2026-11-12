import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MiniDBMS {

    public enum DataType { INTEGER, FLOAT, STRING, BOOLEAN }

    public static class Column implements Serializable {
        private String name;
        private DataType type;
        private boolean primaryKey;

        public Column(String name, DataType type, boolean primaryKey) {
            this.name = name;
            this.type = type;
            this.primaryKey = primaryKey;
        }

        public String getName() { return name; }
        public DataType getType() { return type; }
        public boolean isPrimaryKey() { return primaryKey; }
    }

    public static class Table implements Serializable {
        private String name;
        private List<Column> columns;
        private List<List<Object>> rows;
        private Map<String, Map<Object, List<Integer>>> indexes;

        public Table(String name, List<Column> columns) {
            this.name = name;
            this.columns = columns;
            this.rows = new ArrayList<>();
            this.indexes = new HashMap<>();
        }

        public String getName() { return name; }
        public List<Column> getColumns() { return columns; }
        public List<List<Object>> getRows() { return rows; }

        public Column getColumn(String colName) {
            for (Column c : columns) {
                if (c.getName().equalsIgnoreCase(colName)) return c;
            }
            return null;
        }

        public int getColumnIndex(String colName) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).getName().equalsIgnoreCase(colName)) return i;
            }
            return -1;
        }

        public void createIndex(String colName) {
            if (getColumn(colName) == null) {
                throw new RuntimeException("Column not found: " + colName);
            }
            Map<Object, List<Integer>> indexMap = new HashMap<>();
            int colIdx = getColumnIndex(colName);
            for (int i = 0; i < rows.size(); i++) {
                Object val = rows.get(i).get(colIdx);
                indexMap.computeIfAbsent(val, k -> new ArrayList<>()).add(i);
            }
            indexes.put(colName, indexMap);
            System.out.println("[Index] Created and built index on column: " + colName);
        }

        public void updateIndexes(int rowIndex, List<Object> newRow) {
            for (String colName : indexes.keySet()) {
                int colIdx = getColumnIndex(colName);
                Object val = newRow.get(colIdx);
                Map<Object, List<Integer>> indexMap = indexes.get(colName);
                for (List<Integer> list : indexMap.values()) {
                    list.remove(Integer.valueOf(rowIndex));
                }
                indexMap.computeIfAbsent(val, k -> new ArrayList<>()).add(rowIndex);
            }
        }
    }

    public static class Database {
        private Map<String, Table> tables = new HashMap<>();
        private final File dataDir = new File("./data");

        public Database() {
            if (!dataDir.exists()) dataDir.mkdirs();
            loadAll();
        }

        public Table getTable(String name) {
            return tables.get(name.toLowerCase());
        }

        public void addTable(Table table) {
            tables.put(table.getName().toLowerCase(), table);
            saveTable(table);
        }

        public void saveTable(Table table) {
            File file = new File(dataDir, table.getName() + ".db");
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                oos.writeObject(table);
            } catch (IOException e) {
                System.err.println("Error saving table " + table.getName() + ": " + e.getMessage());
            }
        }

        public void loadAll() {
            File[] files = dataDir.listFiles((dir, name) -> name.endsWith(".db"));
            if (files != null) {
                for (File file : files) {
                    try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                        Table table = (Table) ois.readObject();
                        tables.put(table.getName().toLowerCase(), table);
                        System.out.println("Loaded table: " + table.getName() + " (" + table.getRows().size() + " rows)");
                    } catch (Exception e) {
                        System.err.println("Error loading file " + file.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    public static class ExecutionEngine {
        private Database db;

        public ExecutionEngine(Database db) {
            this.db = db;
        }

        public void execute(String sql) {
            sql = sql.trim();
            if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1);

            try {
                String upper = sql.toUpperCase();
                if (upper.startsWith("CREATE TABLE")) {
                    handleCreateTable(sql);
                } else if (upper.startsWith("CREATE INDEX")) {
                    handleCreateIndex(sql);
                } else if (upper.startsWith("INSERT INTO")) {
                    handleInsert(sql);
                } else if (upper.startsWith("SELECT")) {
                    handleSelect(sql);
                } else if (upper.startsWith("UPDATE")) {
                    handleUpdate(sql);
                } else if (upper.startsWith("DELETE")) {
                    handleDelete(sql);
                } else {
                    System.out.println("Unknown or unsupported command.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }

        private void handleCreateTable(String sql) {
            Pattern p = Pattern.compile("CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(sql);
            if (!m.find()) throw new IllegalArgumentException("Invalid CREATE TABLE syntax");

            String tableName = m.group(1);
            String[] colDefs = m.group(2).split(",");
            List<Column> columns = new ArrayList<>();

            for (String def : colDefs) {
                def = def.trim();
                String[] parts = def.split("\\s+");
                String name = parts[0];
                String typeStr = parts[1].toUpperCase();
                boolean isPk = def.toUpperCase().contains("PRIMARY KEY");

                DataType type;
                if (typeStr.contains("INT")) type = DataType.INTEGER;
                else if (typeStr.contains("FLOAT") || typeStr.contains("DOUBLE")) type = DataType.FLOAT;
                else if (typeStr.contains("BOOL")) type = DataType.BOOLEAN;
                else type = DataType.STRING;

                columns.add(new Column(name, type, isPk));
            }

            Table table = new Table(tableName, columns);
            db.addTable(table);
            System.out.println("Table " + tableName + " created successfully.");
        }

        private void handleCreateIndex(String sql) {
            Pattern p = Pattern.compile("CREATE\\s+INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\((\\w+)\\)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(sql);
            if (!m.find()) throw new IllegalArgumentException("Invalid CREATE INDEX syntax");

            String tableName = m.group(2);
            String colName = m.group(3);

            Table table = db.getTable(tableName);
            if (table == null) throw new RuntimeException("Table not found: " + tableName);

            table.createIndex(colName);
            db.saveTable(table);
        }

        private void handleInsert(String sql) {
            Pattern p = Pattern.compile("INSERT\\s+INTO\\s+(\\w+)(?:\\s*\\((.*?)\\))?\\s+VALUES\\s*\\((.*?)\\)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(sql);
            if (!m.find()) throw new IllegalArgumentException("Invalid INSERT syntax");

            String tableName = m.group(1);
            String valuesStr = m.group(3);

            Table table = db.getTable(tableName);
            if (table == null) throw new RuntimeException("Table not found: " + tableName);

            String[] rawValues = valuesStr.split(",");
            List<Object> newRow = new ArrayList<>();

            if (rawValues.length != table.getColumns().size()) {
                throw new RuntimeException("Values count doesn't match columns count");
            }

            for (int i = 0; i < rawValues.length; i++) {
                String valStr = rawValues[i].trim();
                Column col = table.getColumns().get(i);
                Object val = parseValue(valStr, col.getType());

                if (col.isPrimaryKey()) {
                    int colIdx = i;
                    boolean exists = table.getRows().stream().anyMatch(r -> r.get(colIdx).equals(val));
                    if (exists) {
                        throw new RuntimeException("Primary key violation: value " + val + " already exists.");
                    }
                }
                newRow.add(val);
            }

            table.getRows().add(newRow);
            table.updateIndexes(table.getRows().size() - 1, newRow);
            db.saveTable(table);
            System.out.println("1 row inserted.");
        }

        private void handleSelect(String sql) {
            Pattern p = Pattern.compile("SELECT\\s+(.*?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.*))?", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(sql);
            if (!m.find()) throw new IllegalArgumentException("Invalid SELECT syntax");

            String colsTarget = m.group(1).trim();
            String tableName = m.group(2).trim();
            String whereClause = m.group(3);

            Table table = db.getTable(tableName);
            if (table == null) throw new RuntimeException("Table not found: " + tableName);

            List<Integer> rowIndices = new ArrayList<>();
            for (int i = 0; i < table.getRows().size(); i++) rowIndices.add(i);

            if (whereClause != null) {
                whereClause = whereClause.trim();
                String[] parts = whereClause.split("(=|<|>)");
                if (parts.length == 2) {
                    String colName = parts[0].trim();
                    String valStr = parts[1].trim();
                    char op = whereClause.charAt(colName.length());
                    Column col = table.getColumn(colName);
                    Object targetVal = parseValue(valStr, col.getType());

                    if (op == '=' && table.indexes.containsKey(colName)) {
                        List<Integer> indexedRows = table.indexes.get(colName).get(targetVal);
                        rowIndices = indexedRows != null ? indexedRows : new ArrayList<>();
                        System.out.println("[Optimization] Used index for column: " + colName);
                    } else {
                        List<Integer> filtered = new ArrayList<>();
                        int colIdx = table.getColumnIndex(colName);
                        for (int i : rowIndices) {
                            Object cellVal = table.getRows().get(i).get(colIdx);
                            if (compare(cellVal, targetVal, op)) {
                                filtered.add(i);
                            }
                        }
                        rowIndices = filtered;
                    }
                }
            }

            List<Integer> selectedColIndices = new ArrayList<>();
            if (colsTarget.equals("*")) {
                for (int i = 0; i < table.getColumns().size(); i++) selectedColIndices.add(i);
            } else {
                String[] targetCols = colsTarget.split(",");
                for (String tc : targetCols) {
                    int idx = table.getColumnIndex(tc.trim());
                    if (idx == -1) throw new RuntimeException("Column not found: " + tc);
                    selectedColIndices.add(idx);
                }
            }

            printTableResult(table, selectedColIndices, rowIndices);
        }

        private void handleUpdate(String sql) {
            Pattern p = Pattern.compile("UPDATE\\s+(\\w+)\\s+SET\\s+(\\w+)\\s*=\\s*(.*?)(?:\\s+WHERE\\s+(.*))?", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(sql);
            if (!m.find()) throw new IllegalArgumentException("Invalid UPDATE syntax");

            String tableName = m.group(1);
            String setCol = m.group(2).trim();
            Object newVal = parseValue(m.group(3).trim(), db.getTable(tableName).getColumn(setCol).getType());
            String whereClause = m.group(4);

            Table table = db.getTable(tableName);
            if (table == null) throw new RuntimeException("Table not found: " + tableName);

            int setColIdx = table.getColumnIndex(setCol);
            int updatedCount = 0;

            for (int i = 0; i < table.getRows().size(); i++) {
                List<Object> row = table.getRows().get(i);
                boolean match = true;
                if (whereClause != null) {
                    String[] parts = whereClause.split("(=|<|>)");
                    String colName = parts[0].trim();
                    Object targetVal = parseValue(parts[1].trim(), table.getColumn(colName).getType());
                    char op = whereClause.charAt(colName.length());
                    Object cellVal = row.get(table.getColumnIndex(colName));
                    match = compare(cellVal, targetVal, op);
                }

                if (match) {
                    row.set(setColIdx, newVal);
                    table.updateIndexes(i, row);
                    updatedCount++;
                }
            }

            db.saveTable(table);
            System.out.println(updatedCount + " row(s) updated.");
        }

        private void handleDelete(String sql) {
            Pattern p = Pattern.compile("DELETE\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.*))?", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(sql);
            if (!m.find()) throw new IllegalArgumentException("Invalid DELETE syntax");

            String tableName = m.group(1);
            String whereClause = m.group(2);

            Table table = db.getTable(tableName);
            if (table == null) throw new RuntimeException("Table not found: " + tableName);

            List<List<Object>> newRows = new ArrayList<>();
            int deletedCount = 0;

            for (List<Object> row : table.getRows()) {
                boolean match = false;
                if (whereClause != null) {
                    String[] parts = whereClause.split("(=|<|>)");
                    String colName = parts[0].trim();
                    Object targetVal = parseValue(parts[1].trim(), table.getColumn(colName).getType());
                    char op = whereClause.charAt(colName.length());
                    Object cellVal = row.get(table.getColumnIndex(colName));
                    match = compare(cellVal, targetVal, op);
                } else {
                    match = true;
                }

                if (match) {
                    deletedCount++;
                } else {
                    newRows.add(row);
                }
            }

            table.rows = newRows;
            for (String colName : new HashSet<>(table.indexes.keySet())) {
                table.createIndex(colName);
            }

            db.saveTable(table);
            System.out.println(deletedCount + " row(s) deleted.");
        }

        private Object parseValue(String valStr, DataType type) {
            valStr = valStr.trim();
            if (valStr.startsWith("'") && valStr.endsWith("'")) {
                return valStr.substring(1, valStr.length() - 1);
            }
            if (type == DataType.INTEGER) return Integer.parseInt(valStr);
            if (type == DataType.FLOAT) return Double.parseDouble(valStr);
            if (type == DataType.BOOLEAN) return Boolean.parseBoolean(valStr);
            return valStr;
        }

        private boolean compare(Object val1, Object val2, char op) {
            if (val1 instanceof Number && val2 instanceof Number) {
                double d1 = ((Number) val1).doubleValue();
                double d2 = ((Number) val2).doubleValue();
                if (op == '=') return d1 == d2;
                if (op == '>') return d1 > d2;
                if (op == '<') return d1 < d2;
            } else if (val1 instanceof String && val2 instanceof String) {
                int cmp = ((String) val1).compareTo((String) val2);
                if (op == '=') return cmp == 0;
            } else if (val1 instanceof Boolean && val2 instanceof Boolean) {
                if (op == '=') return val1.equals(val2);
            }
            return false;
        }

        private void printTableResult(Table table, List<Integer> colIndices, List<Integer> rowIndices) {
            StringBuilder header = new StringBuilder("| ");
            for (int idx : colIndices) {
                header.append(table.getColumns().get(idx).getName()).append(" | ");
            }
            System.out.println(header);
            System.out.println("-".repeat(header.length()));

            for (int rIdx : rowIndices) {
                List<Object> row = table.getRows().get(rIdx);
                StringBuilder sb = new StringBuilder("| ");
                for (int cIdx : colIndices) {
                    sb.append(row.get(cIdx)).append(" | ");
                }
                System.out.println(sb);
            }
            System.out.println("(" + rowIndices.size() + " rows in set)\n");
        }
    }

    public static void main(String[] args) {
        Database db = new Database();
        ExecutionEngine engine = new ExecutionEngine(db);
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Mini-DBMS Console (Java) ===");
        System.out.println("Type SQL queries ending with ';' or 'EXIT' to quit.\n");

        while (true) {
            System.out.print("MiniDB> ");
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("EXIT")) break;
            if (line.isEmpty()) continue;

            engine.execute(line);
        }
        scanner.close();
    }
}
