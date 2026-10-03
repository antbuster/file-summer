package com.filesummer.core;

import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation;

import java.io.File;

/**
 * User-defined Groovy keep-predicate. The script body is evaluated per file with
 * arguments f (java.io.File), relPath (relative to scan root, '/' separated) and
 * name; a truthy result keeps the file. GradleScriptGenerator emits the same body
 * as a method, so in-app and generated-project semantics match.
 */
public final class GroovyFilter {

    private final Closure<Object> predicate;

    private GroovyFilter(Closure<Object> predicate) {
        this.predicate = predicate;
    }

    @SuppressWarnings("unchecked")
    public static GroovyFilter compile(String script) {
        GroovyShell shell = new GroovyShell();
        Object compiled = shell.evaluate(
                "return { java.io.File f, String relPath, String name ->\n" + script + "\n}");
        return new GroovyFilter((Closure<Object>) compiled);
    }

    public boolean keep(File f, String relPath, String name) {
        Object result = predicate.call(new Object[]{f, relPath, name});
        // Groovy truth exactly as the generated Gradle script's `!keep(...)` applies it;
        // DefaultGroovyMethods.asBoolean(Object) would stringify and mis-truth a Boolean.
        return DefaultTypeTransformation.booleanUnbox(result);
    }
}
