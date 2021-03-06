package waldap.core.util

import java.io.File

object Directory {
  val WaldapHome: String = (System.getProperty("waldap.home") match {
    case path if path != null =>
      new File(path)
    case _ =>
      scala.util.Properties.envOrNone("WALDAP_HOME") match {
        case Some(env) =>
          new File(env)
        case None =>
          new File(System.getProperty("user.home"), ".waldap")
      }
  }).getAbsolutePath

  val WaldapConf: File = new File(WaldapHome, "waldap.conf")

  val DatabaseHome: String = s"$WaldapHome/data"

  val InstanceHome: String = s"$WaldapHome/server"
}
