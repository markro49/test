//package daikon;

import daikon.util.*;
import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;

/*>>>
import org.checkerframework.checker.nullness.qual.*;
*/

/**
 * This is the main class for MockJar. 
 * This class parses the command line arguments.
 * with the javaagent switch on the target program. Code based largely on daikon.Chicory.
 */
public class MockJar {

  @Option("-d Dump the instrumented classes to disk")
  public static boolean debug = false;

  @Option("-f Output filename for modified jar file")
  public static /*@Nullable*/ String output_file_name = null;

  @Option("-h Output usage message")
  public static boolean help = false;

  @Option("-r File containing a list of packages to be modified and renamed")
  public static /*@Nullable*/ File replacements_file;

  @Option("-v Print information about the classes being transformed")
  public static boolean verbose = false;

  @Option("Source jar file to be processed")
  public static /*@Nullable*/ File input_file = null;

  public static final String synopsis = "java MockJar [options] source-jar-file";

  private static final SimpleLog basic = new SimpleLog(false);

  private static Map<String, String> packageMap = new HashMap<String, String>();

  /**
   * Entry point of MockJar
   *
   * @param args see usage for argument descriptions
   */
  public static void main(String[] args) {

    // Parse our arguments
    Options options = new Options(synopsis, MockJar.class);
    // options.ignore_options_after_arg (true);
    String[] target_args = options.parse_or_usage(args);
    boolean ok = check_args(options, target_args);
    if (!ok) System.exit(1);

    // Turn on basic logging if the debug was selected
    basic.enabled = debug;
    basic.log("non Option args = %s%n", Arrays.toString(target_args));

    // Process replacements file
    try {
      read_replacements(replacements_file);
    } catch (Exception e) {
      System.out.println(e.getMessage());
      System.exit(1);
    }

    // Process jar file
    try {
      process_jar(input_file);
    } catch (Exception e) {
      System.out.println(e.getMessage());
      System.exit(1);
    }

    basic.log("done");
  }

  /**
   * Check the resulting arguments for legality. Prints a message and returns false if there was an
   * error.
   */
  public static boolean check_args(Options options, String[] target_args) {

    if (help) {
      options.print_usage();
      return false;
    }

    // Make sure arguments have legal values

    if (target_args.length == 0) {
      if (input_file == null) {
        options.print_usage("source jar file must be specified");
        return false;
      }
    } else {
      if (input_file != null) {
        options.print_usage("source jar file specified both as option argument and positional argument");
        return false;
      }
      input_file = new File(target_args[0]);
    }

    if (!input_file.exists()) {
      options.print_usage("source jar file %s does not exist", input_file);
      return false;
    }

    if (replacements_file == null) {
      options.print_usage("no package mapping file specified");
      return false;
    }

    if (!replacements_file.exists()) {
      options.print_usage("package mapping file %s does not exist", replacements_file);
      return false;
    }

    return true;
  }

  public static void fatal_error(String msg) {
    System.out.println("error: " + msg);
    System.exit(1);
  }

  private static void read_replacements(final File file) throws Exception {
    basic.log("opening " + file);
    FileReader fr = new FileReader(file);
    LineNumberReader reader = new LineNumberReader(fr);
    String line;
    String[] tokens;
    int num_read = 0;
    int num_processed = 0;

    basic.log("processing " + file);

    while ((line = reader.readLine()) != null) {
      num_read++;
      basic.log("line: " + line);
      line = line.trim();
      if (line.length() == 0) continue; // skip blank lines
      if (line.charAt(0) == '#') continue; // skip # comment lines

      tokens = line.split("\\s");
      if (tokens.length != 2) {
        System.out.printf("warning: incorrect input at line: %d%n  contents: %s%n", reader.getLineNumber(), line);
        continue;
      }
      if (packageMap.containsKey(tokens[0])) {
        System.out.printf("warning: duplicate input package name at line: %d%n  contents: %s%n", reader.getLineNumber(), line);
        continue;
      }
      packageMap.put(tokens[0], tokens[1]);
      num_processed++;
    }
    reader.close();
    
    if (num_read == 0) {
      fatal_error("replacements file empty or ill formed");
    }
    
    if (num_processed == 0) {
      fatal_error("no valid records found in replacements file");
    }

    basic.log("num_read: " + num_read);
    basic.log("num_processed: " + num_processed);
  }

  private static void process_jar(final File file) throws Exception {
    basic.log("opening " + file);
    FileInputStream fis = new FileInputStream(file);
    JarInputStream jis = new JarInputStream(fis);
    JarEntry je = null;
    int num_read = 0;
    int num_processed = 0;

    basic.log("processing " + file);

    while ((je = jis.getNextJarEntry()) != null) {
      String name = je.getName();
      num_read++;

      if (name.endsWith(".class")) {
        basic.log("checking " + name);
        try {
          final ClassParser parser = new ClassParser(jis, name);
          final JavaClass jc = parser.parse();
          String newPackageName = packageMap.get(jc.getPackageName());
          if (newPackageName != null) {
            // DO IT!
            System.out.println(jc.getClassName());
            num_processed++;
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      } else {
        basic.log("skipping " + name);
      }
    }
    jis.close();

    if (num_read == 0) {
      fatal_error("source jar file empty or ill formed");
    }

    if (num_processed == 0) {
      fatal_error("no matching package names found in source jar file");
    }

    basic.log("num_read: " + num_read);
    basic.log("num_processed: " + num_processed);
  }

}
