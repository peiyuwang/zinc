package sbt

package internal
package inc
package javac

import java.io.File

import sbt.io.syntax._
import sbt.io.IO
import sbt.util.Logger
import xsbti.{ Logger => XLogger }
import xsbti.Reporter
import xsbti.compile.{ ClassFileManager, JavaCompiler => XJavaCompiler, Javadoc => XJavadoc }

import scala.sys.process.Process

/** Helper methods for running the java toolchain by forking. */
object ForkedJava {
  /** Helper method to launch programs. */
  private[javac] def launch(javaHome: Option[File], program: String, sources: Seq[File], options: Seq[String], log: Logger, reporter: Reporter): Boolean = {
    val (jArgs, nonJArgs) = options.partition(_.startsWith("-J"))
    val allArguments = nonJArgs ++ sources.map(_.getAbsolutePath)

    withArgumentFile(allArguments) { argsFile =>
      val forkArgs = jArgs :+ s"@${normalizeSlash(argsFile.getAbsolutePath)}"
      val exe = getJavaExecutable(javaHome, program)
      val cwd = new File(new File(".").getAbsolutePath).getCanonicalFile
      val javacLogger = new JavacLogger(log, reporter, cwd)
      var exitCode = -1
      try {
        exitCode = Process(exe +: forkArgs, cwd) ! javacLogger
      } finally {
        javacLogger.flush(exitCode)
      }
      // We return true or false, depending on success.
      exitCode == 0
    }
  }

  /**
   * Helper method to create an argument file that we pass to Javac.  Gets over the windows
   * command line length limitation.
   * @param args The string arguments to pass to Javac.
   * @param f  A function which is passed the arg file.
   * @tparam T The return type.
   * @return  The result of using the argument file.
   */
  def withArgumentFile[T](args: Seq[String])(f: File => T): T =
    {
      import IO.{ Newline, withTemporaryDirectory, write }
      withTemporaryDirectory { tmp =>
        val argFile = new File(tmp, "argfile")
        write(argFile, args.map(escapeSpaces).mkString(Newline))
        f(argFile)
      }
    }
  // javac's argument file seems to allow naive space escaping with quotes.  escaping a quote with a backslash does not work
  private def escapeSpaces(s: String): String = '\"' + normalizeSlash(s) + '\"'
  private def normalizeSlash(s: String) = s.replace(File.separatorChar, '/')

  import sbt.io.Path._
  /** create the executable name for java */
  private[javac] def getJavaExecutable(javaHome: Option[File], name: String): String =
    javaHome match {
      case None => name
      case Some(jh) =>
        // TODO - Was there any hackery for windows before?
        (jh / "bin" / name).getAbsolutePath
    }
}

/** An implementation of compiling java which forks a Javac instance. */
final class ForkedJavaCompiler(javaHome: Option[File]) extends XJavaCompiler {
  def run(sources: Array[File], options: Array[String], classFileManager: ClassFileManager,
    reporter: Reporter, log: XLogger): Boolean =
    ForkedJava.launch(javaHome, "javac", sources, options, log, reporter)
}
final class ForkedJavadoc(javaHome: Option[File]) extends XJavadoc {
  def run(sources: Array[File], options: Array[String], classFileManager: ClassFileManager,
    reporter: Reporter, log: XLogger): Boolean =
    ForkedJava.launch(javaHome, "javadoc", sources, options, log, reporter)
}
