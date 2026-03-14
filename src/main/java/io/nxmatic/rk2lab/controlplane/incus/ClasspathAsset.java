package io.nxmatic.rk2lab.controlplane.incus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Resolved classpath-backed asset loaded from the application JAR/resources.
 */
public record ClasspathAsset(String uri, String resourcePath, String content, String sha256) {

    public static ClasspathAsset load(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Classpath asset uri must be non-empty.");
        }
        if (!uri.startsWith("classpath:")) {
            throw new IllegalArgumentException(
                    "Only classpath-backed asset URIs are supported at this stage. Expected prefix 'classpath:' but got: " + uri
            );
        }

        final String rawPath = uri.substring("classpath:".length()).trim();
        final String normalizedPath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        if (normalizedPath.isBlank()) {
            throw new IllegalArgumentException("Classpath asset uri must include a resource path: " + uri);
        }

        try (InputStream stream = openResource(normalizedPath)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Classpath asset not found: " + uri + " (resource '" + normalizedPath + "')."
                                + " Checked context, declaring-class, and system classloaders."
                );
            }
            final byte[] bytes = stream.readAllBytes();
            return new ClasspathAsset(uri, normalizedPath, new String(bytes, StandardCharsets.UTF_8), sha256(bytes));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read classpath asset: " + uri, ex);
        }
    }

    private static InputStream openResource(String normalizedPath) {
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            final InputStream stream = contextClassLoader.getResourceAsStream(normalizedPath);
            if (stream != null) {
                return stream;
            }
        }

        final ClassLoader declaringClassLoader = ClasspathAsset.class.getClassLoader();
        if (declaringClassLoader != null) {
            final InputStream stream = declaringClassLoader.getResourceAsStream(normalizedPath);
            if (stream != null) {
                return stream;
            }
        }

        final InputStream classRelativeStream = ClasspathAsset.class.getResourceAsStream("/" + normalizedPath);
        if (classRelativeStream != null) {
            return classRelativeStream;
        }

        final ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        if (systemClassLoader != null) {
            return systemClassLoader.getResourceAsStream(normalizedPath);
        }
        return null;
    }

    private static String sha256(byte[] bytes) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(bytes);
            final StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }
}
