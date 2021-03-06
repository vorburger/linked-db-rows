///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.oser.tools.jdbc:linked-db-rows:0.2-SNAPSHOT
//DEPS info.picocli:picocli:4.5.0
import static java.lang.System.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine;
import org.oser.tools.jdbc.*;
import org.oser.tools.jdbc.cli.DynJarLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import static picocli.CommandLine.*;

import java.util.List;
import java.util.concurrent.Callable;

/** Json export via script, needs https://www.jbang.dev/
 *  call 'jbang JsonExport.java -h' for help */
@Command(name = "JsonExport", mixinStandardHelpOptions = true, version = "JsonExport 0.2",
        description = "Exports a table and its linked tables as JSON. Writes the JSON to stdout (other output to stderr), you can use > myFile.json to get it in a file.", showDefaultValues = true)
public class JsonExport implements Callable<Integer> {

	@Option(names = {"-u","--url"}, description = "jdbc connection-url")
    private String url = "jdbc:postgresql://localhost/demo";
	
	@Option(names = {"-t","--tableName"}, description = "table name to export")
    private String tableName = "blogpost";
	
	@Option(names = {"-p","--pkValue"}, description = "Primary key value of the root table to export")
    private String pkValue = "2";

    @Option(names = {"-l","--login"}, description = "Login name of database")
    private String username = "postgres";

    @Option(names = {"-pw","--password"}, description = "Password")
    private String password = "admin";

    @Option(names = {"--stopTablesExcluded"}, description = "Stop tables excluded, comma-separated")
    private List<String> stopTablesExcluded;

    @Option(names = {"--stopTablesIncluded"}, description = "Stop tables included, comma-separated")
    private List<String> stopTablesIncluded;

    @Option(names = {"--canon"}, description = "Should we canonicalize the primary keys of the output? (default: false)")
    private boolean doCanonicalize = false;

    @Option(names = {"-db"}, description = "What jdbc driver to use? (default:postgres) ")
    private String  databaseShortName = "postgres";


    public static void main(String... args) throws SQLException, ClassNotFoundException {
		int exitCode = new CommandLine(new JsonExport()).execute(args);
        System.exit(exitCode);
    }

	@Override
	public Integer call() throws SQLException, ClassNotFoundException, JsonProcessingException, Exception {
        err.println("Exporting table "+tableName);
		DbExporter dbExporter = new DbExporter();
		if (stopTablesExcluded != null){
            err.println("stopTablesExcluded:"+stopTablesExcluded);
		    dbExporter.getStopTablesExcluded().addAll(stopTablesExcluded);
        }
        if (stopTablesIncluded != null){
            err.println("stopTablesIncluded:"+stopTablesIncluded);
            dbExporter.getStopTablesIncluded().addAll(stopTablesIncluded);
        }

        Connection connection = DynJarLoader.getConnection(databaseShortName, url, username, password, this.getClass().getClassLoader());
        if (connection == null) {
            err.println("Could not get jdbc connection for:"+databaseShortName);
            return -1;
        }
        Record asRecord = dbExporter.contentAsTree(connection, tableName, pkValue);

        if (doCanonicalize) {
            RecordCanonicalizer.canonicalizeIds(connection, asRecord, dbExporter.getFkCache(), dbExporter.getPkCache());
        }

        ObjectMapper mapper = Record.getObjectMapper();
        String asString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(asRecord.asJsonNode());
		err.println("Data: \n");
		out.println(asString);
		return 0;
	}

}
