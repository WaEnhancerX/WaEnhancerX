#!/usr/bin/env python3
"""Parse Java 17 and Kotlin sources without Android SDK or Gradle.

This checks syntax only, not type resolution, dependencies, Android APIs,
Gradle configuration, bytecode compatibility or runtime Xposed behavior.

Usage:
  python3 qa/check_syntax.py
  python3 qa/check_syntax.py --root /path/to/source --json
  python3 qa/check_syntax.py --java-only --root /path/to/source

Exit codes: 0 = all requested languages parsed, 1 = syntax errors,
2 = missing/broken toolchain or input. Kotlin checks require installed
Kotlin CLI compiler JARs (KOTLIN_HOME or a 'kotlinc' installation).
If Kotlin sources exist, a missing Kotlin compiler is never a silent pass.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OMIT_DIRS = frozenset({
    ".git", ".gradle", ".idea", ".kotlin", ".venv", "venv",
    "build", "out", "target", "node_modules", "__pycache__",
})
EXIT_SYNTAX = 1
EXIT_INFRA = 2
TIMEOUT_SECONDS = 180

JAVA_RUNNER = r'''
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import com.sun.source.util.JavacTask;

public final class WaexJavaSyntaxRunner {
    public static void main(String[] paths) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler unavailable");
        List<File> files = new ArrayList<>();
        for (String path : paths) files.add(new File(path));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> sources = manager.getJavaFileObjectsFromFiles(files);
            JavacTask task = (JavacTask) compiler.getTask(
                    null, manager, diagnostics,
                    Arrays.asList("-proc:none", "--release", "17"), null, sources);
            task.parse();
        }
        int count = 0;
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                count++;
                String file = d.getSource() == null ? "<unknown>" :
                        new File(d.getSource().toUri()).getPath();
                String message = d.getMessage(java.util.Locale.ROOT)
                        .replace('\n', ' ').replace('\t', ' ');
                System.out.println("SYNTAX_ERROR\tjava\t" + file + "\t" +
                        d.getLineNumber() + "\t" + message);
            }
        }
        System.out.println("SYNTAX_TOTAL\tjava\t" + files.size() + "\t" + count);
    }
}
'''

KOTLIN_RUNNER = r'''
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;

public final class WaexKotlinSyntaxRunner {
    /**
     * Kotlin 2.4+ requires the compiler configuration factory to initialize
     * extension storage and diagnostic services; bare new CompilerConfiguration()
     * throws "Extensions storage is not registered". Kotlin 1.9 uses the
     * legacy constructor. An incompatible NEW factory fails closed.
     */
    private static CompilerConfiguration newConfiguration() throws ReflectiveOperationException {
        // Kotlin 1.9 CompilerConfiguration is a Java class without Companion.
        // Kotlin 2.4+ provides a companion extension factory initializing services.
        Object companion;
        try {
            companion = CompilerConfiguration.class.getField("Companion").get(null);
        } catch (NoSuchFieldException oldCompiler) {
            return new CompilerConfiguration();
        }

        // File facade name has changed across Kotlin compiler distributions.
        // Do not fall back to the bare constructor for a modern compiler.
        String[] factories = {
                "org.jetbrains.kotlin.cli.CompilerConfigurationCreationKt",
                "org.jetbrains.kotlin.cli.CreateKt"
        };
        for (String className : factories) {
            try {
                Class<?> holder = Class.forName(className);
                java.lang.reflect.Method create = holder.getMethod("create", companion.getClass());
                CompilerConfiguration configured =
                        (CompilerConfiguration) create.invoke(null, companion);
                if (configured == null)
                    throw new IllegalStateException("Kotlin compiler factory returned null");
                return configured;
            } catch (ClassNotFoundException | NoSuchMethodException absentVariant) {
                // Continue to the next documented compiler facade.
            }
        }
        throw new IllegalStateException(
                "Kotlin companion configuration factory missing from supported compiler JARs");
    }

    public static void main(String[] paths) throws Exception {
        Disposable disposable = Disposer.newDisposable();
        int errors = 0;
        try {
            KotlinCoreEnvironment env = KotlinCoreEnvironment.createForProduction(
                    disposable, newConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
            KtPsiFactory factory = new KtPsiFactory(env.getProject(), false);
            for (String path : paths) {
                File file = new File(path);
                // IntelliJ PSI requires LF even when Git checks out CRLF on Windows.
                // Normalize the parser input only; never rewrite production sources.
                String input = Files.readString(file.toPath()).replace("\r\n", "\n").replace('\r', '\n');
                KtFile kt = factory.createFile(file.getName(), input);
                for (PsiErrorElement error : PsiTreeUtil.collectElementsOfType(kt, PsiErrorElement.class)) {
                    errors++;
                    String message = error.getErrorDescription().replace('\n', ' ').replace('\t', ' ');
                    int line = 1;
                    CharSequence content = kt.getViewProvider().getDocument() == null
                            ? kt.getText() : kt.getViewProvider().getDocument().getCharsSequence();
                    for (int offset = 0; offset < error.getTextOffset() && offset < content.length(); offset++) {
                        if (content.charAt(offset) == '\n') line++;
                    }
                    System.out.println("SYNTAX_ERROR\tkotlin\t" + path + "\t" + line + "\t" + message);
                }
            }
        } finally {
            Disposer.dispose(disposable);
        }
        System.out.println("SYNTAX_TOTAL\tkotlin\t" + paths.length + "\t" + errors);
    }
}
'''


def sources(root: Path) -> dict[str, list[Path]]:
    collected: dict[str, list[Path]] = {"java": [], "kotlin": []}
    for directory, dirs, filenames in os.walk(root, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d not in OMIT_DIRS and not (Path(directory) / d).is_symlink())
        for name in sorted(filenames):
            extension = Path(name).suffix
            if extension not in (".java", ".kt"):
                continue
            file = Path(directory) / name
            if file.is_symlink() or not file.is_file():
                continue
            collected["java" if extension == ".java" else "kotlin"].append(file.resolve())
    for entries in collected.values():
        entries.sort()
    return collected


def kotlin_library() -> Path | None:
    if "KOTLIN_HOME" in os.environ:
        base = Path(os.environ["KOTLIN_HOME"]).expanduser()
    else:
        compiler = shutil.which("kotlinc")
        if not compiler:
            return None
        base = Path(compiler).resolve().parent.parent
    directory = base / "lib"
    if (directory / "kotlin-compiler.jar").is_file():
        return directory
    return None


def _execute(command: list[str], *, cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                          timeout=TIMEOUT_SECONDS, encoding="utf-8", errors="replace", check=False)


def _diagnostics(output: str, root: Path, language: str) -> tuple[list[dict], bool]:
    errors: list[dict] = []
    saw_total = False
    for line in output.splitlines():
        if line.startswith("SYNTAX_TOTAL\t"):
            parts = line.split("\t", 3)
            if len(parts) == 4 and parts[1] == language:
                saw_total = True
        elif line.startswith("SYNTAX_ERROR\t"):
            parts = line.split("\t", 4)
            if len(parts) != 5 or parts[1] != language:
                raise ValueError("Malformed parser diagnostic")
            file = Path(parts[2])
            try:
                file_label = str(file.relative_to(root))
            except ValueError:
                file_label = str(file)
            errors.append({"language": language, "file": file_label,
                           "line": int(parts[3]), "message": parts[4]})
    return errors, saw_total


def check(root: Path, *, java_only: bool = False) -> tuple[int, dict]:
    report: dict = {
        "root": str(root), "status": "error", "java_files": 0,
        "kotlin_files": 0, "kotlin_skipped": False, "syntax_errors": [],
        "toolchain_errors": [], "limitations": (
            "Syntax only; does not compile Android, resolve symbols, run Gradle, "
            "or verify LSPosed/WhatsApp runtime compatibility.")
    }
    if not root.is_dir():
        report["toolchain_errors"].append("Source root does not exist")
        return EXIT_INFRA, report
    found = sources(root)
    java_files, kotlin_files = found["java"], found["kotlin"]
    report["java_files"], report["kotlin_files"] = len(java_files), len(kotlin_files)
    if not java_files and not kotlin_files:
        report["toolchain_errors"].append("No .java or .kt source files found")
        return EXIT_INFRA, report
    if java_only and not java_files:
        report["toolchain_errors"].append("--java-only requested but no Java sources found")
        return EXIT_INFRA, report
    java_bin, javac_bin = shutil.which("java"), shutil.which("javac")
    if not java_bin or not javac_bin:
        report["toolchain_errors"].append("JDK java and javac are required")
        return EXIT_INFRA, report
    try:
        with tempfile.TemporaryDirectory(prefix="waex_syntax_") as temp:
            working = Path(temp)
            if java_files:
                runner = working / "WaexJavaSyntaxRunner.java"
                runner.write_text(JAVA_RUNNER, encoding="utf-8")
                built = _execute([javac_bin, "--release", "17", "-d", temp, str(runner)], cwd=root)
                if built.returncode:
                    raise RuntimeError("Cannot compile Java syntax runner: " + built.stderr[-1300:])
                result = _execute([java_bin, "-Xmx800m", "-cp", temp,
                                   "WaexJavaSyntaxRunner", *map(str, java_files)], cwd=root)
                parsed, complete = _diagnostics(result.stdout, root, "java")
                report["syntax_errors"].extend(parsed)
                if result.returncode or not complete:
                    raise RuntimeError("Java parser failed: " + (result.stderr or result.stdout)[-1300:])
            if kotlin_files and java_only:
                report["kotlin_skipped"] = True
            elif kotlin_files:
                libs = kotlin_library()
                if libs is None:
                    raise RuntimeError("Kotlin compiler JARs unavailable: set KOTLIN_HOME or install kotlinc. "
                                       "Use --java-only only for an explicitly partial check.")
                runner = working / "WaexKotlinSyntaxRunner.java"
                runner.write_text(KOTLIN_RUNNER, encoding="utf-8")
                classpath = str(libs / "*")
                built = _execute([javac_bin, "--release", "17", "-proc:none", "-cp", classpath,
                                  "-d", temp, str(runner)], cwd=root)
                if built.returncode:
                    raise RuntimeError("Cannot compile Kotlin PSI syntax runner: " + built.stderr[-1500:])
                result = _execute([java_bin, "-Xmx1200m", "-Djava.awt.headless=true",
                                   "-cp", os.pathsep.join((temp, classpath)),
                                   "WaexKotlinSyntaxRunner", *map(str, kotlin_files)], cwd=root)
                parsed, complete = _diagnostics(result.stdout, root, "kotlin")
                report["syntax_errors"].extend(parsed)
                if result.returncode or not complete:
                    raise RuntimeError("Kotlin PSI parser failed: " + (result.stderr or result.stdout)[-1500:])
    except (OSError, ValueError, subprocess.TimeoutExpired, RuntimeError) as error:
        report["toolchain_errors"].append(str(error))
        report["status"] = "infrastructure_error"
        return EXIT_INFRA, report
    report["status"] = "syntax_error" if report["syntax_errors"] else (
        "partial_java_only" if report["kotlin_skipped"] else "passed")
    return (EXIT_SYNTAX if report["syntax_errors"] else 0), report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="Source tree to scan")
    parser.add_argument("--java-only", action="store_true", help="Explicitly skip Kotlin (partial validation)")
    parser.add_argument("--json", action="store_true", help="Machine-readable report")
    args = parser.parse_args(argv)
    root = args.root.expanduser().resolve()
    code, report = check(root, java_only=args.java_only)
    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print(f"Syntax check: {report['status']} — {report['java_files']} Java, "
              f"{report['kotlin_files']} Kotlin files discovered")
        if report["kotlin_skipped"]:
            print("PARTIAL: Kotlin sources explicitly skipped (--java-only)")
        for error in report["syntax_errors"]:
            print(f"{error['language']} syntax: {error['file']}:{error['line']}: {error['message']}")
        for error in report["toolchain_errors"]:
            print("TOOLCHAIN ERROR: " + error, file=sys.stderr)
        print(report["limitations"])
    return code


if __name__ == "__main__":
    raise SystemExit(main())
