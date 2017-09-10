package waldap.core.service

import javax.servlet.http.HttpServletRequest

import waldap.core.util.SyntaxSugars._
import waldap.core.util.ConfigUtil._
import waldap.core.util.Directory
import waldap.core.util.Implicits._
import SystemSettingsService._

trait SystemSettingsService {

  def saveSystemSettings(settings: SystemSettings): Unit = {
    defining(new java.util.Properties()){ props =>
      settings.baseUrl.foreach(x => props.setProperty(BaseURL, x.replace("/\\Z", "")))
      settings.baseDn.foreach(x => props.setProperty(BaseDN, x.replace("/\\Z", "")))
      props.setProperty(LdapPort, settings.ldapPort.toString)
      using(new java.io.FileOutputStream(Directory.LdapandaConf)){ out =>
        props.store(out, null)
      }
    }
  }

  def loadSystemSettings(): SystemSettings = {
    defining(new java.util.Properties()){ props =>
      if(Directory.LdapandaConf.exists){
        using(new java.io.FileInputStream(Directory.LdapandaConf)){ in =>
          props.load(in)
        }
      }
      SystemSettings(
        getOptionValue[String](props, BaseURL, None).map(x => x.replaceFirst("/\\Z", "")),
        getOptionValue[String](props, BaseDN, None).map(x => x.replaceFirst("/\\Z", "")),
        getValue[Int](props, LdapPort, 10389)
      )
    }
  }
}

object SystemSettingsService {
  import scala.reflect.ClassTag

  case class SystemSettings(
    baseUrl: Option[String],
    baseDn: Option[String],
    ldapPort: Int
  ){
    def baseUrl(request: HttpServletRequest): String = baseUrl.fold(request.baseUrl)(_.stripSuffix("/"))
  }

  private val BaseURL = "base_url"
  private val BaseDN = "base_dn"
  private val LdapPort = "ldap.port"

  private def getValue[A: ClassTag](props: java.util.Properties, key: String, default: A): A = {
    getSystemProperty(key).getOrElse(getEnvironmentVariable(key).getOrElse {
      defining(props.getProperty(key)){ value =>
        if(value == null || value.isEmpty){
          default
        } else {
          convertType(value).asInstanceOf[A]
        }
      }
    })
  }

  private def getOptionValue[A: ClassTag](props: java.util.Properties, key: String, default: Option[A]): Option[A] = {
    getSystemProperty(key).orElse(getEnvironmentVariable(key).orElse {
      defining(props.getProperty(key)){ value =>
        if(value == null || value.isEmpty){
          default
        } else {
          Some(convertType(value)).asInstanceOf[Option[A]]
        }
      }
    })
  }
}