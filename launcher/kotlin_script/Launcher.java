package kotlin_script;

import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Launcher implements X509TrustManager, HostnameVerifier, Runnable {

    private final String javaVersion;
    private final boolean trace;
    private final boolean force;
    private final boolean progress;

    private String progressMsg;
    private long progressTotal;
    private long progressDone;
    private final Object progressLock = new Object();
    private Thread progressThread;

    private final String centralRepo;
    private final Path localMirror;
    private final Path localRepo;

    private final String kotlinVersion = "1.9.21";
    private final String kotlinScriptVersion = kotlinVersion + ".22";
    private final Path cacheDir;

    private final String[] dependencies = new String[] {
            // BEGIN_KOTLIN_SCRIPT_DEPENDENCY_FILE_NAMES
            "org/cikit/kotlin_script/1.9.21.22/kotlin_script-1.9.21.22.jar",
            "com/github/ajalt/mordant/mordant-jvm/2.2.0/mordant-jvm-2.2.0.jar",
            "com/github/ajalt/colormath/colormath-jvm/3.3.1/colormath-jvm-3.3.1.jar",
            "org/jetbrains/markdown-jvm/0.5.2/markdown-jvm-0.5.2.jar",
            "org/jetbrains/kotlin/kotlin-stdlib/1.9.21/kotlin-stdlib-1.9.21.jar",
            "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar",
            "it/unimi/dsi/fastutil-core/8.5.12/fastutil-core-8.5.12.jar",
            // END_KOTLIN_SCRIPT_DEPENDENCY_FILE_NAMES
    };

    private final byte[][] checksums = new byte[][] {
            // BEGIN_KOTLIN_SCRIPT_DEPENDENCY_CHECKSUMS
            new byte[]{-67, 41, -35, 72, 88, -84, -37, 65, -3, -78, 92, 112, 29, -56, -119, -15, 109, 95, 21, -119, -56, -73, -64, 62, 89, -103, -52, 48, -122, 57, 14, -128},
            new byte[]{47, -91, -98, 91, -127, -81, -52, 113, -74, -85, 97, 40, -78, 118, 118, -1, -58, 17, -48, -93, -63, -126, -21, -64, -28, 55, -77, 102, 106, -75, 98, 1},
            new byte[]{38, 19, 40, 52, 21, -30, -31, 38, 97, 105, 125, -57, 41, 90, -38, -38, 15, 96, -20, 23, -20, -4, -81, 62, -15, -60, -18, 15, -37, 120, -119, 19},
            new byte[]{114, 100, -124, 71, 114, 96, -91, 82, -36, 124, 25, -80, -99, 70, 86, -60, -118, -29, -79, 13, -78, 114, 80, 0, -31, -126, -42, 21, -107, 41, -76, 110},
            new byte[]{59, 71, -109, 19, -85, 108, -82, -92, -27, -30, 93, 61, -18, -116, -88, 12, 48, 44, -119, -70, 115, -31, -81, 77, -81, -86, 16, 15, 110, -7, 41, 106},
            new byte[]{102, -44, -8, 25, -96, 98, -91, 26, 29, 86, 39, -65, -4, 35, -6, -59, 93, 22, 119, -16, -32, -95, -2, -70, 20, 74, -85, -35, 103, 10, 100, -69},
            new byte[]{-13, 28, 32, -11, -80, 99, 18, -13, -43, -32, 110, 97, 96, -93, 46, 39, 77, -127, -102, -90, -50, -65, 39, 82, -117, 38, -74, -75, -64, -63, -33, 25},
            // END_KOTLIN_SCRIPT_DEPENDENCY_CHECKSUMS
    };
    private final long[] sizes = new long[] {
            // BEGIN_KOTLIN_SCRIPT_DEPENDENCY_SIZES
            63667L,
            553121L,
            353666L,
            539307L,
            1718945L,
            1879325L,
            6428331L,
            // END_KOTLIN_SCRIPT_DEPENDENCY_SIZES
    };

    private SSLSocketFactory _sf = null;
    private final MessageDigest md;

    private final Path scriptFile;
    private final byte[] scriptFileData;
    private final String scriptFileSha256;
    private final Path scriptMetadata;

    private Launcher(Path scriptFile) throws NoSuchAlgorithmException, IOException {
        final String javaVersionProperty = System.getProperty("java.vm.specification.version");
        final String javaVersionDefault = "1.8";
        if (javaVersionProperty != null && isNotBlank(javaVersionProperty)) {
            //verify version string
            int st = 0;
            for (char ch : javaVersionProperty.toCharArray()) {
                if (st == 0) {
                    if (!Character.isDigit(ch)) {
                        st = -1;
                        break;
                    }
                    st = 1;
                } else {
                    if (ch == '.') {
                        st = 0;
                    } else if (!Character.isDigit(ch)) {
                        st = -1;
                        break;
                    }
                }
            }
            if (st != 1) {
                System.err.println("warning: ignored invalid java.vm.specification.version: " + javaVersionProperty);
                javaVersion = javaVersionDefault;
            } else {
                javaVersion = javaVersionProperty;
            }
        } else {
            javaVersion = javaVersionDefault;
        }

        final String kotlinScriptFlagsEnv = System.getProperty("kotlin_script.flags");
        if (kotlinScriptFlagsEnv != null) {
            this.trace = kotlinScriptFlagsEnv.contains("-x");
            this.force = kotlinScriptFlagsEnv.contains("-f");
            this.progress = kotlinScriptFlagsEnv.contains("-P");
        } else {
            this.trace = false;
            this.force = false;
            this.progress = false;
        }

        final String centralRepoEnv = System.getenv("M2_CENTRAL_REPO");
        if (centralRepoEnv != null && isNotBlank(centralRepoEnv)) {
            centralRepo = centralRepoEnv;
        } else {
            centralRepo = "https://repo1.maven.org/maven2";
        }

        final String localMirrorEnv = System.getenv("M2_LOCAL_MIRROR");
        if (localMirrorEnv != null && isNotBlank(localMirrorEnv)) {
            localMirror = Paths.get(localMirrorEnv);
        } else {
            localMirror = null;
        }

        final String localRepoEnv = System.getenv("M2_LOCAL_REPO");
        if (localRepoEnv != null && isNotBlank(localRepoEnv)) {
            localRepo = Paths.get(localRepoEnv.trim());
        } else {
            final Path userHome = Paths.get(System.getProperty("user.home"));
            localRepo = userHome.resolve(".m2/repository");
        }

        cacheDir = localRepo.resolve("org/cikit/kotlin_script_cache/" + kotlinScriptVersion);

        this.md = MessageDigest.getInstance("SHA-256");
        this.scriptFile = scriptFile;
        try (InputStream in = Files.newInputStream(scriptFile)) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                md.reset();
                copy(in, out, md);
                out.flush();
                this.scriptFileData = out.toByteArray();
                this.scriptFileSha256 = hexDigest(md);
            }
        }
        this.scriptMetadata = cacheDir.resolve("kotlin_script_cache-" +
                kotlinScriptVersion + "-sha256=" + scriptFileSha256 + ".metadata");
    }

    private SSLSocketFactory getSocketFactory() throws IOException {
        if (this._sf == null) {
            try {
                final SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{this}, new SecureRandom());
                this._sf = context.getSocketFactory();
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new IOException(e);
            }
        }
        return this._sf;
    }

    @Override
    public boolean verify(String hostname, SSLSession session) {
        return true;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    @Override
    public void run() {
        String spinner = "|\\-/";
        int offset = 0;
        long lastPrint = -1L;
        while (progressMsg != null) {
            try {
                synchronized (progressLock) {
                    final long thisPrint = System.currentTimeMillis();
                    if (thisPrint - lastPrint >= 200L) {
                        if (offset > 0) {
                            System.err.write((byte) 0x0d);
                        }
                        System.err.write((byte) spinner.charAt((offset / 2) % spinner.length()));
                        offset++;
                        final String msg = String.format("   %.2f%%  %s",
                                ((double) progressDone) / ((double) progressTotal) * 100.0,
                                progressMsg);
                        System.err.write(msg.getBytes(StandardCharsets.US_ASCII));
                        System.err.flush();
                        lastPrint = thisPrint;
                    }
                    progressLock.wait(200);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        if (offset > 0) {
            System.err.write((byte) 0x0d);
            System.err.flush();
        }
    }

    private void copy(InputStream in, OutputStream out, MessageDigest md) throws IOException {
        final byte[] buffer = new byte[4096];
        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            if (progressTotal > 0L) {
                synchronized (progressLock) {
                    progressDone += read;
                    progressLock.notify();
                }
            }
            md.update(buffer, 0, read);
            out.write(buffer, 0, read);
        }
    }

    private Path createTempFile(Path target) throws IOException {
        final Path targetDir;
        if (target.getParent() == null) {
            targetDir = Paths.get(".");
        } else {
            targetDir = target.getParent();
        }
        if (!Files.isDirectory(targetDir)) {
            Files.createDirectories(targetDir);
        }
        return Files.createTempFile(targetDir, target.getFileName().toString(), "~");
    }

    private boolean copyFromLocalMirror(String sourcePath, Path target, byte[] sha256) throws IOException {
        final Path source = localMirror.resolve(sourcePath);
        if (!Files.isReadable(source)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(source)) {
            final Path tmp = createTempFile(target);
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    if (trace) {
                        System.err.println("++ cp " + source + " " + target);
                    }
                    md.reset();
                    copy(in, out, md);
                }
                final byte[] actualSha256 = md.digest();
                if (Arrays.equals(sha256, actualSha256)) {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    return true;
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        return false;
    }

    private long fetch(String sourcePath, Path target, byte[] sha256, long size, boolean dry) throws IOException {
        if (localMirror != null && copyFromLocalMirror(sourcePath, target, sha256)) {
            return 0;
        }
        if (dry) {
            return size;
        }
        final URL source = new URL(centralRepo + "/" + sourcePath);
        final Path tmp = createTempFile(target);
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                if (trace) {
                    System.err.println("++ fetch -o " + target + " " + source);
                }
                final URLConnection cn = source.openConnection();
                if (cn instanceof HttpsURLConnection) {
                    ((HttpsURLConnection) cn).setSSLSocketFactory(getSocketFactory());
                    ((HttpsURLConnection) cn).setHostnameVerifier(this);
                }
                try (InputStream in = cn.getInputStream()) {
                    md.reset();
                    copy(in, out, md);
                    final byte[] actualSha256 = md.digest();
                    if (!Arrays.equals(sha256, actualSha256)) {
                        final String expected = hexString(sha256);
                        final String actual = hexString(actualSha256);
                        throw new RuntimeException(source + ": sha mismatch: " + actual + " != " + expected);
                    }
                }
            }
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return Files.size(target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static boolean isNotBlank(String input) {
        for (int i = 0; i < input.length(); i++) {
            if (!Character.isWhitespace(input.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String hexString(byte[] input) {
        final StringBuilder sb = new StringBuilder();
        for (byte b : input) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static String hexDigest(MessageDigest md) {
        return hexString(md.digest());
    }

    private static String hexDigest(MessageDigest md, Path f) throws IOException {
        md.reset();
        try (InputStream in = Files.newInputStream(f)) {
            final byte[] buffer = new byte[4096];
            while (true) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                md.update(buffer, 0, read);
            }
            return hexDigest(md);
        }
    }

    private void executeCachedJar(Path compiledJar, String[] args) throws IOException, ClassNotFoundException,
            NoSuchMethodException, InstantiationException, InvocationTargetException, IllegalAccessException {
        final Path scriptDir;
        if (scriptFile.getParent() == null) {
            scriptDir = Paths.get(".");
        } else {
            scriptDir = scriptFile.getParent();
        }

        // read metadata
        if (trace) {
            System.err.println("++ read_metadata " + scriptMetadata);
        }
        final List<String> dependencies = new ArrayList<>();
        final List<String> includes = new ArrayList<>();
        String mainClass = null;
        try (BufferedReader reader = Files.newBufferedReader(scriptMetadata)) {
            while (true) {
                final String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (line.startsWith("///RDEP=")) {
                    dependencies.add(line.substring(8));
                } else if (line.startsWith("///DEP=")) {
                    dependencies.add(line.substring(7));
                } else if (line.startsWith("///INC=")) {
                    includes.add(line.substring(7));
                } else if (line.startsWith("///MAIN=")) {
                    mainClass = line.substring(8);
                }
            }
        }

        if (mainClass == null) {
            final StringBuilder sb = new StringBuilder();
            final String fn = scriptFile.getFileName().toString();
            if (!fn.isEmpty()) {
                sb.append(fn.substring(0, 1).toUpperCase());
            }
            int fromIndex = 1;
            while (fromIndex < fn.length()) {
                int i = fn.indexOf('.', fromIndex);
                if (i < 0) {
                    sb.append(fn.substring(fromIndex));
                    break;
                }
                sb.append(fn, fromIndex, i);
                sb.append('_');
            }
            final String s = sb.toString();
            if (s.endsWith("_kt")) {
                mainClass = s.substring(0, s.length() - 3) + "Kt";
            } else if (s.endsWith("_kts")) {
                mainClass = s.substring(0, s.length() - 4);
            } else {
                mainClass = s;
            }
        }

        // check dependencies
        final URL[] classPath = new URL[dependencies.size() + 1];
        int i = 1;
        for (String dependency : dependencies) {
            final Path dependencyFile = localRepo.resolve(dependency);
            if (!Files.isReadable(dependencyFile)) {
                throw new RuntimeException("dependency not readable: " + dependency);
            }
            classPath[i++] = dependencyFile.toUri().toURL();
        }

        final Path jarToExecute;

        if (compiledJar == null) {
            // check includes
            final String targetSha256;
            if (includes.isEmpty()) {
                targetSha256 = scriptFileSha256;
            } else {
                final StringBuilder sb = new StringBuilder();
                sb.append("sha256=").append(scriptFileSha256).append(" ")
                        .append(scriptFile.getFileName().toString());
                for (String inc : includes) {
                    final String sha256 = hexDigest(md, scriptDir.resolve(inc));
                    sb.append("\nsha256=").append(sha256).append(" ").append(inc);
                }
                sb.append("\n");
                md.reset();
                md.update(sb.toString().getBytes(StandardCharsets.UTF_8));
                targetSha256 = hexDigest(md);
            }

            Path cachedJar;
            String tryJavaVersion = javaVersion;
            while (true) {
                if (tryJavaVersion != null) {
                    cachedJar = cacheDir.resolve("kotlin_script_cache-" +
                            kotlinScriptVersion + "-java" + tryJavaVersion +
                            "-sha256=" + targetSha256 + ".jar");
                } else {
                    cachedJar = cacheDir.resolve("kotlin_script_cache-" +
                            kotlinScriptVersion + "-sha256=" + targetSha256 + ".jar");
                }
                if (trace) {
                    System.err.println("++ test -r " + cachedJar);
                }
                if (tryJavaVersion == null || Files.isReadable(cachedJar)) {
                    break;
                }
                if (tryJavaVersion.equals("1.8")) {
                    tryJavaVersion = null;
                } else if (tryJavaVersion.equals("9")) {
                    tryJavaVersion = "1.8";
                } else {
                    int javaVersionNum = Integer.parseInt(tryJavaVersion);
                    tryJavaVersion = Integer.toString(javaVersionNum - 1);
                }
            }

            jarToExecute = cachedJar;
        } else {
            jarToExecute = compiledJar;
        }

        if (trace) {
            System.err.println("++ " + mainClass + ".main(" + Arrays.toString(args) + ")");
        }
        classPath[0] = jarToExecute.toUri().toURL();
        final URLClassLoader cl = new URLClassLoader(classPath, Launcher.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        final Class<?> clazz = cl.loadClass(mainClass);
        final Method mainMethod = clazz.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) args);
    }

    private Path executeCompiler() throws IOException, ClassNotFoundException,
            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        final URL[] classPath = new URL[dependencies.length];
        progressTotal = 0L;
        if (progress && !trace) {
            for (int i = 0; i < dependencies.length; i++) {
                final String relPath = dependencies[i];
                final byte[] sha256 = checksums[i];
                final long size = sizes[i];
                final Path targetPath = localRepo.resolve(relPath);
                if (!Files.isReadable(targetPath)) {
                    progressTotal += fetch(relPath, targetPath, sha256, size, true);
                }
            }
        }
        if (progressTotal > 0L) {
            progressMsg = "fetching kotlin_script compiler";
            progressThread = new Thread(this);
            progressThread.setDaemon(true);
            progressThread.start();
        }
        try {
            for (int i = 0; i < dependencies.length; i++) {
                final String relPath = dependencies[i];
                final byte[] sha256 = checksums[i];
                final long size = sizes[i];
                final Path targetPath = localRepo.resolve(relPath);
                if (!Files.isReadable(targetPath)) {
                    fetch(relPath, targetPath, sha256, size, false);
                }
                classPath[i] = targetPath.toUri().toURL();
            }
        } finally {
            if (progressTotal > 0L) {
                synchronized (progressLock) {
                    progressMsg = null;
                    progressLock.notify();
                }
                progressTotal = 0L;
            }
        }

        if (trace) {
            System.err.println("++ compileScript " +
                    scriptFile + " byte[" +
                    scriptFileData.length + "] " +
                    scriptFileSha256 + " " +
                    scriptMetadata);
        }

        final URLClassLoader cl = new URLClassLoader(classPath, Launcher.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        final Class<?> clazz = cl.loadClass("kotlin_script.KotlinScript");
        final Method compileScriptMethod = clazz.getMethod(
                "compileScript",
                Path.class,   //            scriptFile: Path,
                byte[].class, //            scriptData: ByteArray,
                String.class, //            scriptFileSha256: String,
                Path.class    //            scriptMetadata: Path
        );
        return (Path) compileScriptMethod.invoke(null, scriptFile, scriptFileData, scriptFileSha256, scriptMetadata);
    }

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException, KeyManagementException, InvocationTargetException {
        if (args.length == 0) {
            System.err.println("usage: Launcher /path/to/script [ARG...]");
            System.exit(2);
        }

        final String scriptName = args[0];
        final String scriptFlags = System.getenv("KOTLIN_SCRIPT_FLAGS");
        System.setProperty("kotlin_script.name", scriptName);
        if (scriptFlags == null) {
            System.setProperty("kotlin_script.flags", "");
        } else {
            System.setProperty("kotlin_script.flags", scriptFlags);
        }

        final Launcher launcher = new Launcher(Paths.get(scriptName));
        final Path expectedLauncherPath = launcher.localRepo.resolve(
                "org/cikit/kotlin_script/" + launcher.kotlinScriptVersion +
                        "/kotlin_script-" + launcher.kotlinScriptVersion + ".sh");
        final URL launcherJarUrl = Launcher.class.getResource("/kotlin_script/Launcher.class");
        if (launcherJarUrl != null && launcherJarUrl.toString().startsWith("jar:file:")) {
            final String launcherJarPath1 = launcherJarUrl.toString().substring(9);
            final int i = launcherJarPath1.lastIndexOf('!');
            final String launcherJarPath;
            if (i < 0) {
                launcherJarPath = launcherJarPath1;
            } else {
                launcherJarPath = launcherJarPath1.substring(0, i);
            }
            Path actualLauncherPath = Paths.get(launcherJarPath);
            if (!Files.exists(expectedLauncherPath) || !Files.isSameFile(expectedLauncherPath, actualLauncherPath)) {
                final Path targetDir = expectedLauncherPath.getParent();
                if (targetDir != null && !Files.isDirectory(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                Files.copy(actualLauncherPath, expectedLauncherPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        final String[] scriptArgs = new String[args.length - 1];
        System.arraycopy(args, 1, scriptArgs, 0, args.length - 1);

        if (!launcher.force) {
            try {
                launcher.executeCachedJar(null, scriptArgs);
                return;
            } catch (InvocationTargetException e) {
                final Throwable cause = e.getCause();
                if (cause == null) {
                    throw e;
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new RuntimeException(cause);
            } catch (Exception e) {
                // execute cached jar failed -> run compiler
            }
        }

        final Path targetJar;

        try {
            targetJar = launcher.executeCompiler();
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause == null) {
                throw e;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            launcher.executeCachedJar(targetJar, scriptArgs);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause == null) {
                throw e;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
