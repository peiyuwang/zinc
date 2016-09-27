package sbt

package internal
package inc
package javac

import java.io.{ File, OutputStream, PrintWriter, Writer }
import javax.tools.JavaFileManager.Location
import javax.tools.JavaFileObject.Kind
import javax.tools.{ FileObject, ForwardingFileObject, ForwardingJavaFileManager, ForwardingJavaFileObject, JavaFileManager, JavaFileObject }

import sbt.internal.util.LoggerWriter
import sbt.util.{ Level, Logger }
import xsbti.compile.ExternalHooks.ClassFileManager
import xsbti.{ Reporter, Logger => XLogger }
import xsbti.compile.{ JavaCompiler => XJavaCompiler, Javadoc => XJavadoc }

/**
 * Helper methods for trying to run the java toolchain out of our own classloaders.
 */
object LocalJava {
  private[this] val javadocClass = "com.sun.tools.javadoc.Main"

  private[this] def javadocMethod =
    try {
      Option(Class.forName(javadocClass).getDeclaredMethod("execute", classOf[String], classOf[PrintWriter], classOf[PrintWriter], classOf[PrintWriter], classOf[String], classOf[Array[String]]))
    } catch {
      case e @ (_: ClassNotFoundException | _: NoSuchMethodException) => None
    }

  /** True if we can call a forked Javadoc. */
  def hasLocalJavadoc: Boolean = javadocMethod.isDefined

  /** A mechanism to call the javadoc tool via reflection. */
  private[javac] def unsafeJavadoc(args: Array[String], err: PrintWriter, warn: PrintWriter, notice: PrintWriter): Int = {
    javadocMethod match {
      case Some(m) =>
        System.err.println("Running javadoc tool!")
        m.invoke(null, "javadoc", err, warn, notice, "com.sun.tools.doclets.standard.Standard", args).asInstanceOf[java.lang.Integer].intValue
      case _ =>
        System.err.println("Unable to reflectively invoke javadoc, cannot find it on the current classloader!")
        -1
    }
  }
}
/** Implementation of javadoc tool which attempts to run it locally (in-class). */
final class LocalJavadoc() extends XJavadoc {
  override def run(sources: Array[File], options: Array[String], classFileManager: ClassFileManager,
    reporter: Reporter, log: XLogger): Boolean = {
    val cwd = new File(new File(".").getAbsolutePath).getCanonicalFile
    val (jArgs, nonJArgs) = options.partition(_.startsWith("-J"))
    val allArguments = nonJArgs ++ sources.map(_.getAbsolutePath)
    val javacLogger = new JavacLogger(log, reporter, cwd)
    val warnOrError = new PrintWriter(new ProcessLoggerWriter(javacLogger, Level.Error))
    val infoWriter = new PrintWriter(new ProcessLoggerWriter(javacLogger, Level.Info))
    var exitCode = -1
    try {
      exitCode = LocalJava.unsafeJavadoc(allArguments.toArray, warnOrError, warnOrError, infoWriter)
    } finally {
      warnOrError.close()
      infoWriter.close()
      javacLogger.flush(exitCode)
    }
    // We return true or false, depending on success.
    exitCode == 0
  }
}

/** An implementation of compiling java which delegates to the JVM resident java compiler. */
final class LocalJavaCompiler(compiler: javax.tools.JavaCompiler) extends XJavaCompiler {
  override def run(sources: Array[File], options: Array[String], classFileManager: ClassFileManager,
    reporter: Reporter, log0: XLogger): Boolean = {
    val log: Logger = log0
    import collection.JavaConverters._
    val logger = new LoggerWriter(log)
    val logWriter = new PrintWriter(logger)
    log.debug("Attempting to call " + compiler + " directly...")
    val diagnostics = new DiagnosticsReporter(reporter)
    val fileManager = compiler.getStandardFileManager(diagnostics, null, null)
    val jfiles = fileManager.getJavaFileObjectsFromFiles(sources.toList.asJava)

    // Local Java compiler doesn't accept `-J<flag>` options. We emit a warning if we find
    // such options and don't pass them to the compiler.
    val (invalidOptions, cleanedOptions) = options partition (_ startsWith "-J")
    if (invalidOptions.nonEmpty) {
      log.warn("Javac is running in 'local' mode. These flags have been removed:")
      log.warn(invalidOptions.mkString("\t", ", ", ""))
    }
    val writeReportingFileManager = if (classFileManager == null) fileManager
    else new WriteReportingFileManager(fileManager, classFileManager)
    val success = compiler.getTask(logWriter, writeReportingFileManager,
      diagnostics, cleanedOptions.toList.asJava, null, jfiles).call()

    // The local compiler may report a successful compilation even though there are errors (e.g. encoding problems in the
    // source files). In a similar situation, command line javac reports a failed compilation. To have the local java compiler
    // stick to javac's behavior, we report a failed compilation if there have been errors.
    success && !diagnostics.hasErrors
  }
}

final class WriteReportingFileManager(fileManager: JavaFileManager, var classFileManager: ClassFileManager)
  extends ForwardingJavaFileManager[JavaFileManager](fileManager) {
  override def getFileForOutput(location: Location, packageName: String, relativeName: String, sibling: FileObject): FileObject = {
    new WriteReportingFileObject(super.getFileForOutput(location, packageName, relativeName, sibling), classFileManager)
  }

  override def getJavaFileForOutput(location: Location, className: String, kind: Kind, sibling: FileObject): JavaFileObject = {
    new WriteReportingJavaFileObject(super.getJavaFileForOutput(location, className, kind, sibling), classFileManager)
  }
}

final class WriteReportingFileObject(fileObject: FileObject, var classFileManager: ClassFileManager)
  extends ForwardingFileObject[FileObject](fileObject) {
  override def openWriter(): Writer = {
    classFileManager.generated(Array(new File(fileObject.toUri)))
    super.openWriter()
  }

  override def openOutputStream(): OutputStream = {
    classFileManager.generated(Array(new File(fileObject.toUri)))
    super.openOutputStream()
  }
}

final class WriteReportingJavaFileObject(javaFileObject: JavaFileObject, var classFileManager: ClassFileManager)
  extends ForwardingJavaFileObject[JavaFileObject](javaFileObject) {
  override def openWriter(): Writer = {
    classFileManager.generated(Array(new File(javaFileObject.toUri)))
    super.openWriter()
  }

  override def openOutputStream(): OutputStream = {
    classFileManager.generated(Array(new File(javaFileObject.toUri)))
    super.openOutputStream()
  }
}
