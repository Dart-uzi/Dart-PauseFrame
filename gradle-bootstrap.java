import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.zip.*;

public class GradleBootstrap {
  static final String VERSION = "9.7.1";
  static final String URL = "https://services.gradle.org/distributions/gradle-" + VERSION + "-bin.zip";
  static final Path ROOT = Path.of(System.getProperty("user.dir"));
  static final Path DIST = ROOT.resolve(".gradle-dist");
  static final Path GRADLE_HOME = DIST.resolve("gradle-" + VERSION);
  public static void main(String[] args) throws Exception {
    Path exe = GRADLE_HOME.resolve("bin").resolve(isWindows() ? "gradle.bat" : "gradle");
    if (!Files.exists(exe)) {
      Files.createDirectories(DIST);
      Path zip = DIST.resolve("gradle-" + VERSION + ".zip");
      if (!Files.exists(zip)) {
        System.out.println("Downloading Gradle " + VERSION + "...");
        try (InputStream in = URI.create(URL).toURL().openStream()) {
          Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
        }
      }
      Path tmp = DIST.resolve("extract-" + VERSION);
      if (Files.exists(tmp)) delete(tmp);
      Files.createDirectories(tmp);
      unzip(zip, tmp);
      Path extracted = tmp.resolve("gradle-" + VERSION);
      if (!Files.exists(extracted)) throw new IOException("Gradle archive did not contain expected directory: " + extracted);
      if (Files.exists(GRADLE_HOME)) delete(GRADLE_HOME);
      Files.move(extracted, GRADLE_HOME, StandardCopyOption.REPLACE_EXISTING);
      delete(tmp);
    }
    java.util.List<String> cmd = new java.util.ArrayList<>();
    cmd.add(exe.toString());
    for (String a : args) cmd.add(a);
    Process p = new ProcessBuilder(cmd).inheritIO().start();
    System.exit(p.waitFor());
  }
  static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
  static void unzip(Path zip, Path out) throws IOException {
    try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry e;
      while ((e = zis.getNextEntry()) != null) {
        Path target = out.resolve(e.getName()).normalize();
        if (!target.startsWith(out)) throw new IOException("Unsafe ZIP entry: " + e.getName());
        if (e.isDirectory()) Files.createDirectories(target);
        else { Files.createDirectories(target.getParent()); Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING); }
      }
    }
  }
  static void delete(Path p) throws IOException {
    if (!Files.exists(p)) return;
    try (var s = Files.walk(p)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (IOException ex) { throw new UncheckedIOException(ex); } }); }
  }
}
