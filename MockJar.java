//package daikon;

import plume.*;
import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import org.apache.bcel.*;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;

/*>>>
import org.checkerframework.checker.nullness.qual.*;
*/

/**
 * This is the main class for MockJar.
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

  private static ConstantPoolGen pgen;

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

/**
 * Read the replacements file and build packageMap of old to new package names.
 */
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

/**
 * Read the source jar file and look for ".class" files whose package matches one of
 * the entries in packageMap.
 */
  private static void process_jar(final File file) throws Exception {
    basic.log("opening " + file);
    FileInputStream fis = new FileInputStream(file);
    JarInputStream jis = new JarInputStream(fis);
    JarEntry jie = null;
    int num_read = 0;
    int num_processed = 0;

    if (output_file_name == null) {
      output_file_name = "mock." + file.getName();
    }
    FileOutputStream fos = new FileOutputStream(output_file_name);
    // do we need manifest?  don't think Java Beans will work without it.
    JarOutputStream jos = new JarOutputStream(fos);

    basic.log("processing " + file);

    while ((jie = jis.getNextJarEntry()) != null) {
      String name = jie.getName();
      num_read++;

      if (name.endsWith(".class")) {
        basic.log("checking " + name);
        try {
          final ClassParser parser = new ClassParser(jis, name);
          final JavaClass jc = parser.parse();
          // ISSUE
          // we may need to iterate over map and see if any key entry
          // matches via startsWith method.
          String oldPackageName = jc.getPackageName();
          String newPackageName = packageMap.get(oldPackageName);
          if (newPackageName != null) {
            ClassGen cg = new ClassGen(jc);
            process_methods(cg);

            // ISSUE
            // do we need to modify SuperclassName?
            String newClassName = cg.getClassName().replaceFirst(oldPackageName, newPackageName);
            cg.setClassName(newClassName);
            basic.log("old Class: " + jc.getClassName() + " Superclass: " + jc.getSuperclassName());

            // write the modified class to the output jar file
            JarEntry joe = new JarEntry(newClassName.replace(".", "/") + ".class");
            jos.putNextEntry(joe);
            byte[] buffer = cg.getJavaClass().getBytes();
            jos.write(buffer, 0, buffer.length);
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
    jos.close();

    if (num_read == 0) {
      fatal_error("source jar file empty or ill formed");
    }

    if (num_processed == 0) {
      fatal_error("no matching package names found in source jar file");
    }

    basic.log("num_read: " + num_read);
    basic.log("num_processed: " + num_processed);
  }

/**
 * Modify the class methods to have no body - except return.
 */
  private static void process_methods(ClassGen cg) {
    int num_read = 0;
    int num_processed = 0;
    String class_name = cg.getClassName();
    StackMap smta;

    basic.log("processing " + class_name);

    pgen = cg.getConstantPool();

    try {
      Method[] methods = cg.getMethods();
      for (int i = 0; i < methods.length; i++) {
        MethodGen mg = new MethodGen(methods[i], class_name, pgen);

        basic.log("processing " + mg.getName());
        num_read++;

        // Get the instruction list and skip methods with no instructions
        InstructionList il = mg.getInstructionList();
        if (il == null) {
           continue;
        }

        // Remove existing StackMapTable (if present)
        smta = (StackMap) get_stack_map_table_attribute(mg);
        if (smta != null) {
           mg.removeCodeAttribute(smta);
        }

        InstructionList new_il = new InstructionList();
        Type result_type = mg.getType();
        if (result_type.getType() != Const.T_VOID) {
          new_il.append(InstructionFactory.createNull(result_type));
        }
        new_il.append(InstructionFactory.createReturn(result_type));

        // Update the instruction list
        mg.setInstructionList(new_il);
        mg.update();

        // Update the max stack
        mg.setMaxStack();
        mg.update();

        // Update the method in the class
        cg.replaceMethod(methods[i], mg.getMethod());
        num_processed++;
      }

      cg.update();
    } catch (Exception e) {
      System.out.printf("Unexpected exception encountered: %s%n", e);
      e.printStackTrace();
      //System.out.println(Arrays.toString(e.getStackTrace()));
    }

    basic.log("num_read: " + num_read);
    basic.log("num_processed: " + num_processed);
  }

  private static Attribute get_stack_map_table_attribute(MethodGen mg) {
    for (Attribute a : mg.getCodeAttributes()) {
      if (is_stack_map_table(a)) {
        return a;
      }
    }
    return null;
  }

  private static boolean is_stack_map_table(Attribute a) {
    return (get_attribute_name(a).equals("StackMapTable"));
  }

  /** Returns the attribute name for the specified attribute. */
  private static String get_attribute_name(Attribute a) {
    int con_index = a.getNameIndex();
    Constant c = pgen.getConstant(con_index);
    String att_name = ((ConstantUtf8) c).getBytes();
    return att_name;
  }

}
