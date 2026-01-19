package tech.kayys.gamelan.engine.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom classloader for plugin isolation
 *
 * Uses parent-last delegation for plugin classes to ensure plugins
 * can use their own versions of dependencies.
 */
public class PluginClassLoader extends URLClassLoader {

    private final List<String> sharedPackages;

    public PluginClassLoader(Path pluginJar, ClassLoader parent) {
        super(new URL[] { toURL(pluginJar) }, parent);
        this.sharedPackages = new ArrayList<>();
        // Always share plugin API classes
        this.sharedPackages.add("tech.kayys.gamelan.plugin");
    }

    /**
     * Add a package to be shared with the parent classloader
     */
    public void addSharedPackage(String packageName) {
        sharedPackages.add(packageName);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // Check if already loaded
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                return c;
            }

            // Check if this is a shared package
            if (isSharedPackage(name)) {
                return super.loadClass(name, resolve);
            }

            // Try to load from plugin first (parent-last)
            try {
                c = findClass(name);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            } catch (ClassNotFoundException e) {
                // Fall back to parent
                return super.loadClass(name, resolve);
            }
        }
    }

    private boolean isSharedPackage(String className) {
        for (String pkg : sharedPackages) {
            if (className.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static URL toURL(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert path to URL: " + path, e);
        }
    }
}
