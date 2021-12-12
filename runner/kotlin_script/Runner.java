package kotlin_script;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

public class Runner implements X509TrustManager, HostnameVerifier {

    private final String javaVersion;
    private final boolean trace;
    private final boolean progress;

    private final String centralRepo;
    private final Path localMirror;
    private final Path localRepo;

    private final String kotlinScriptVersion;
    private final Path cacheDir;

    private final SSLSocketFactory sf;
    private final MessageDigest md;

    private final Path scriptFile;
    private final byte[] scriptFileData;
    private final String scriptFileSha256;
    private final Path scriptMetadata;

    private Runner(Path scriptFile) throws NoSuchAlgorithmException, KeyManagementException, IOException {
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
            this.progress = kotlinScriptFlagsEnv.contains("-P");
        } else {
            this.trace = false;
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

        final String kotlinScriptVersionEnv = System.getenv("KOTLIN_SCRIPT_VERSION");
        if (kotlinScriptVersionEnv != null && isNotBlank(kotlinScriptVersionEnv)) {
            kotlinScriptVersion = kotlinScriptVersionEnv;
        } else {
            throw new IllegalStateException("KOTLIN_SCRIPT_VERSION environment variable not set");
        }

        cacheDir = localRepo.resolve("org/cikit/kotlin_script_cache/" + kotlinScriptVersion);

        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{this}, new SecureRandom());
        this.sf = context.getSocketFactory();

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

    private void copy(InputStream in, OutputStream out, MessageDigest md) throws IOException {
        final byte[] buffer = new byte[4096];
        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            md.update(buffer, 0, read);
            out.write(buffer, 0, read);
        }
    }

    void fetch(String sourcePath, Path target, byte[] sha256) throws IOException {
        final Path targetDir;
        if (target.getParent() == null) {
            targetDir = Paths.get(".");
        } else {
            targetDir = target.getParent();
        }
        if (!Files.isDirectory(targetDir)) {
            Files.createDirectories(targetDir);
        }
        final Path tmp = Files.createTempFile(targetDir, target.getFileName().toString(), "~");
        try {
            boolean needFetch = true;
            if (localMirror != null) {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    final Path source = localMirror.resolve(sourcePath);
                    try (InputStream in = Files.newInputStream(source)) {
                        if (trace) {
                            System.err.println("++ cp " + source + " " + target);
                        }
                        md.reset();
                        copy(in, out, md);
                        final byte[] actualSha256 = md.digest();
                        if (Arrays.equals(sha256, actualSha256)) {
                            needFetch = false;
                        }
                    } catch (IOException e) {
                        //ignored
                    }
                }
            }
            if (needFetch) {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    final URL source = new URL(centralRepo + "/" + sourcePath);
                    if (trace) {
                        System.err.println("++ fetch -o " + target + " " + source);
                    } else if (progress) {
                        System.err.println("fetching " + source);
                    }
                    final URLConnection cn = source.openConnection();
                    if (cn instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) cn).setSSLSocketFactory(sf);
                        ((HttpsURLConnection) cn).setHostnameVerifier(this);
                    }
                    try {
                        try (InputStream in = cn.getInputStream()) {
                            // could provide better progress reporter with cn.getContentLengthLong();
                            md.reset();
                            copy(in, out, md);
                            final byte[] actualSha256 = md.digest();
                            if (!Arrays.equals(sha256, actualSha256)) {
                                final String expected = hexString(sha256);
                                final String actual = hexString(actualSha256);
                                throw new RuntimeException(source + ": sha mismatch: " + actual + " != " + expected);
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
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

    private static byte[] parseHex(String input) {
        final int len = input.length();
        if ((len & 0x01) != 0) throw new IllegalArgumentException("odd input length");
        final byte[] result = new byte[len >> 1];
        int j = 0;
        for (int k = 0; k < len; k ++) {
            final int hi = Character.digit(input.charAt(k++), 16);
            final int lo = Character.digit(input.charAt(k), 16);
            result[j++] = (byte) (((hi << 4) | lo) & 0xFF);
        }
        return result;
    }

    private static byte[] parseHexFromEnv(String variable) {
        final String sha256Env = System.getenv(variable);
        final byte[] sha256;
        if (sha256Env != null && isNotBlank(sha256Env)) {
            final byte[] result;
            try {
                result = parseHex(sha256Env);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(variable + " environment variable has invalid format: " + e.getMessage());
            }
            if (result.length != 32) {
                throw new IllegalStateException(variable + " environment variable has invalid length: expected 64 hex digits");
            }
            sha256 = result;
        } else {
            throw new IllegalStateException(variable + " environment variable not set");
        }
        return sha256;
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
            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
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
            int i = fn.indexOf('.');
            if (i < 0) {
                i = fn.length();
            }
            if (fn.length() > 0) {
                sb.append(fn.substring(0, 1).toUpperCase());
                sb.append(fn, 1, i);
            }
            sb.append("Kt");
            mainClass = sb.toString();
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
            System.err.println("++ main " + Arrays.toString(args));
        }
        classPath[0] = jarToExecute.toUri().toURL();
        final URLClassLoader cl = new URLClassLoader(classPath, Runner.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        final Class<?> clazz = cl.loadClass(mainClass);
        final Method mainMethod = clazz.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) args);
    }

    private Path executeCompiler() throws IOException, ClassNotFoundException,
            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        final String kotlinScriptRelPath =
                "org/cikit/kotlin_script/" + kotlinScriptVersion + "/kotlin_script-" + kotlinScriptVersion + ".jar";
        final Path kotlinScriptPath = localRepo.resolve(kotlinScriptRelPath);
        if (!Files.isReadable(kotlinScriptPath)) {
            final byte[] sha256 = parseHexFromEnv("KOTLIN_SCRIPT_SHA256");
            fetch(kotlinScriptRelPath, kotlinScriptPath, sha256);
        }

        final String kotlinVersion;
        final String kotlinVersionEnv = System.getenv("KOTLIN_VERSION");
        if (kotlinVersionEnv != null && isNotBlank(kotlinVersionEnv)) {
            kotlinVersion = kotlinVersionEnv;
        } else {
            throw new IllegalStateException("KOTLIN_VERSION environment variable not set");
        }
        final String kotlinStdLibRelPath =
                "org/jetbrains/kotlin/kotlin-stdlib/" + kotlinVersion + "/kotlin-stdlib-" + kotlinVersion + ".jar";
        final Path kotlinStdLibPath = localRepo.resolve(kotlinStdLibRelPath);
        if (!Files.isReadable(kotlinStdLibPath)) {
            final byte[] sha256 = parseHexFromEnv("KOTLIN_STDLIB_SHA256");
            fetch(kotlinStdLibRelPath, kotlinStdLibPath, sha256);
        }

        if (trace) {
            System.err.println("++ compileScript " +
                    scriptFile + " byte[" +
                    scriptFileData.length + "] " +
                    scriptFileSha256 + " " +
                    scriptMetadata);
        }
        final URL[] classPath = new URL[]{
                kotlinScriptPath.toUri().toURL(),
                kotlinStdLibPath.toUri().toURL()
        };
        final URLClassLoader cl = new URLClassLoader(classPath, Runner.class.getClassLoader());
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
            System.err.println("usage: Runner /path/to/script [ARG...]");
            System.exit(2);
        }

        final Runner runner = new Runner(Paths.get(args[0]));
        final String[] scriptArgs = new String[args.length - 1];
        System.arraycopy(args, 1, scriptArgs, 0, args.length - 1);

        try {
            runner.executeCachedJar(null, scriptArgs);
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

        final Path targetJar;

        try {
            targetJar = runner.executeCompiler();
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
            runner.executeCachedJar(targetJar, scriptArgs);
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
