#!/usr/bin/env python3
"""Parse the shipped Java + Kotlin sources without requiring Android SDK.

This checks syntax only, NOT linking, Android API compatibility, tests on a
physical phone, or execution of any LSPosed hooks. Requires a local JDK and
kotlinc compiler JAR. Runs in an isolated temporary directory.
"""
import os
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA_FILES = list(ROOT.rglob('*.java'))
KT_FILES = list(ROOT.rglob('*.kt'))
KOTLIN_HOME = (Path(os.environ['KOTLIN_HOME']) if 'KOTLIN_HOME' in os.environ
               else (Path(shutil.which('kotlinc')).resolve().parent.parent
                     if shutil.which('kotlinc') else Path('/nonexistent')))
KOTLIN_LIB = KOTLIN_HOME / 'lib'

RUNNER = r'''
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;

public class SyntaxCheck {
    public static void main(String[] args) throws Exception {
        List<File> java = new ArrayList<>(), kotlin = new ArrayList<>();
        for (String path : args) {
            File file = new File(path);
            if (path.endsWith(".java")) java.add(file);
            else if (path.endsWith(".kt")) kotlin.add(file);
        }
        int errors = 0;
        if (!java.isEmpty()) {
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
                var compilation = manager.getJavaFileObjectsFromFiles(java);
                JavacTask task = (JavacTask)compiler.getTask(null, manager, diagnostics,
                    Arrays.asList("-proc:none", "--release", "17"), null, compilation);
                task.parse();
            }
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    System.err.println("Java syntax: " + d.getSource() + ":" + d.getLineNumber() + " " + d.getMessage(null));
                    errors++;
                }
            }
        }
        if (!kotlin.isEmpty()) {
            var disposable = Disposer.newDisposable();
            try {
                KotlinCoreEnvironment env = KotlinCoreEnvironment.createForProduction(
                    disposable, new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
                KtPsiFactory factory = new KtPsiFactory(env.getProject(), false);
                for (File f : kotlin) {
                    KtFile ast = factory.createFile(f.getName(), Files.readString(f.toPath()));
                    for (PsiErrorElement err : PsiTreeUtil.collectElementsOfType(ast, PsiErrorElement.class)) {
                        System.err.println("Kotlin syntax: " + f + " :: " + err.getErrorDescription());
                        errors++;
                    }
                }
            } finally { Disposer.dispose(disposable); }
        }
        System.out.println("Parsed " + java.size() + " Java + " + kotlin.size() + " Kotlin files; syntax errors=" + errors);
        if (errors != 0) System.exit(1);
    }
}
'''

def main():
    if not KOTLIN_LIB.joinpath('kotlin-compiler.jar').exists():
        sys.exit('SKIPPED: kotlin-compiler.jar not installed; install Kotlin CLI and set KOTLIN_HOME')
    files = [str(p) for p in JAVA_FILES + KT_FILES]
    with tempfile.TemporaryDirectory(prefix='waex_syntax_') as tmp:
        source = Path(tmp) / 'SyntaxCheck.java'
        source.write_text(RUNNER)
        cp = str(KOTLIN_LIB / '*')
        subprocess.run(['javac', '-proc:none', '-cp', cp, '-d', tmp, str(source)], check=True)
        subprocess.run(['java', '-Xmx1200m', '-cp', tmp + ':' + cp, 'SyntaxCheck'] + files, check=True)

if __name__ == '__main__':
    main()
