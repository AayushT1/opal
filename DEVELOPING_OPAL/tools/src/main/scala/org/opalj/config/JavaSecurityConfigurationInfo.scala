package org.opalj.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.util.Properties
import java.io.FileInputStream
import org.opalj.log.OPALLogger
import org.opalj.log.GlobalLogContext
import com.typesafe.config.ConfigRenderOptions
import scala.collection.JavaConverters._

/**
 * This analysis reports the specified security properties of the used JVM. We are in particular interested
 * in the information about the restricted packages. These information can only be leveraged if the security manager
 * is set before starting the program. Setting the SecurityManager in the program's main function is to late since a lot
 * of classes has been load already.
 *
 * The package.definition property is by default not checked by any class loader. Hence, it is not safe to leverage this
 * information.
 *
 *
 * There are two kinds of packages where a "java.lang.SecurityException" is thrown.
 *
 * 1. packages with access restrictions: (package.access property)
 *
 *  List of comma-separated packages that start with or equal this string
 *  will cause a security exception to be thrown when
 *  passed to checkPackageAccess unless the
 *  corresponding RuntimePermission ("accessClassInPackage."+package) has
 *  been granted.
 *
 *  2. packages with (package.definition property)
 *
 *  List of comma-separated packages that start with or equal this string
 *  will cause a security exception to be thrown when
 *  passed to checkPackageDefinition unless the
 *  corresponding RuntimePermission ("defineClassInPackage."+package) has
 *  been granted.
 *
 *  by default, none of the class loaders supplied with the JDK call
 *  checkPackageDefinition.
 *
 * Commandline Arguments:
 * 	-conf | shows the current (merged) opal configuration
 *
 * @author Michael Reif
 */
object JavaSecurityConfigurationInfo {

    val lineSep = System.getProperty("line.separator")

    def main(args: Array[String]): Unit = {
        val config: Config = ConfigFactory.load()

        if (args.exists { _.equals("-conf") }) {
            println("########	PROPERTIES	########\n")
            println(renderConfig(config))
            println(lineSep + lineSep)
        }

        val javaHomeEntry: Option[String] = try {
            Some(config.getString("java.home"))
        } catch {
            case _: Exception ⇒
                OPALLogger.error("", "java home is not specified")(GlobalLogContext)
                None
        }

        if (javaHomeEntry.isDefined) {
            val javaHome = javaHomeEntry.get
            val javaVersion = System.getProperty("java.version")

            println("$JAVA_HOME = "+javaHome)
            println("$JAVA_VERSION = "+javaVersion)
            println()

            assert(javaHome.endsWith(javaVersion), "Java Runtime Environment does not match with read java.security file!")

            println("########	SECURITY INFORMATION	########")
            println()

            val secPropPath = javaHome+"/lib/security/java.security"
            val javaSecurity = new Properties()

            javaSecurity.load(new FileInputStream(secPropPath))

            if (javaSecurity.getProperty("package.access").equals(javaSecurity.getProperty("package.definition"))) {
                OPALLogger.warn("security", "package.access and package.defintion define different packages")(GlobalLogContext)
            }

            javaSecurity.stringPropertyNames().asScala.foreach { property ⇒
                val entry = javaSecurity.getProperty(property)
                if (entry.contains(","))
                    println(entry.split(",").mkString(property+"\n\t\t", "\n\t\t", ""))
                else
                    println(s"$property=$entry")

                println()
            }
        }
    }

    private[this] def renderConfig(config: Config): String = {
        val renderingOptions = ConfigRenderOptions.
            defaults().
            setOriginComments(false).
            setComments(true).
            setJson(false);
        config.root().render(renderingOptions)
    }
}