/*
 *  Copyright 2020 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.cloud.dataproc.auth

import java.nio.file.{Files, Paths}
import java.io.InputStream
import java.security.{ SecureRandom, KeyStore }
import javax.net.ssl.{ SSLContext, TrustManagerFactory, KeyManagerFactory }
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.server.{ Route, Directives }
import akka.http.scaladsl.{ ConnectionContext, HttpsConnectionContext, Http }
import akka.http.scaladsl.coding.{Gzip, NoCoding}
import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaTypes, RemoteAddress}
import akka.http.scaladsl.model.RemoteAddress.IP
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives.{extractLog, extractUnmatchedPath}
import akka.http.scaladsl.server.directives.RouteDirectives.{complete, reject}
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.scaladsl.FileIO
import akka.stream.{ActorMaterializer, Materializer}
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.compute.model.Instance
import com.google.api.services.dataproc.model.{Cluster, ClusterConfig, InstanceGroupConfig}
import com.google.cloud.dataproc.auth.ApiQuery.ClusterFilter
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext


implicit val dispatcher = system.dispatcher

// Manual HTTPS configuration

// sets default context to HTTPS – all Http() bound servers for this ActorSystem will use HTTPS from now on
Http().setDefaultServerHttpContext(https)
Http().bindAndHandle(routes, "127.0.0.1", 9090, connectionContext = https)

// IP address and port as argument
// env variable or accepts arguments command line- main class

// implicit val system = ActorSystem()

// option if , val sslConfig = AkkaSSLConfig()
// openssl s_connect or curl and will give the certificate

// ConnectionContext
def https(
  sslContext:          SSLContext,
  sslConfig:           Option[AkkaSSLConfig]         = None,
  enabledCipherSuites: Option[immutable.Seq[String]] = None,
  enabledProtocols:    Option[immutable.Seq[String]] = None,
  clientAuth:          Option[TLSClientAuth]         = None,
  sslParameters:       Option[SSLParameters]         = None) =
  new HttpsConnectionContext(sslContext, sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)




object AuthService {
  def main(args: Array[String]): Unit = {
    implicit val sys: ActorSystem = ActorSystem()
    implicit val mat: ActorMaterializer = ActorMaterializer()
    implicit val ctx: ExecutionContext = sys.dispatcher
    val password: Array[Char] = "change me".toCharArray // do not store passwords in code, read them from somewhere safe!
    val ks: KeyStore = KeyStore.getInstance("PKCS12")
    #which one the application supports i.e. akkahttp web framework
    val keystore: InputStream = getClass.getClassLoader.getResourceAsStream("server.p12")
    require(keystore != null, "Keystore required!")
    ks.load(keystore, password)
    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)
    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)
    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    val https: HttpsConnectionContext = ConnectionContext.https(sslContext)
    run(AuthServiceConfig.fromEnv)
  }

  def run(config: AuthServiceConfig)
         (implicit sys: ActorSystem, mat: ActorMaterializer, ctx: ExecutionContext): Unit = {
    import config._
    val handler = handle(dir, projectId, zone, maxAgeSeconds, config.audience)
    val settings = ServerSettings(configOverrides = "akka.http.server.remote-address-header = true")
    val ssl_settings = HttpsConnectionContext(sslContext, sslConfig, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)
    val server = Http().bindAndHandle(handler, interface, port, settings = settings,ssl_settings) 
    System.out.println(s"Listening on $interface:$port")
  }

  def hasIp(ip: String, instance: Instance): Boolean =
    instance.getNetworkInterfaces.asScala.exists(_.getNetworkIP == ip)

  def hasIp(ip: RemoteAddress, instance: Instance): Boolean =
    ip match {
      case IP(ip,_) =>
        hasIp(ip.getHostAddress, instance)
      case _ => false
    }

  def getMembers(igc: InstanceGroupConfig): Seq[String] =
    igc.getInstanceNames.asScala

  def getMembers(cc: ClusterConfig): Set[String] = (
    Option(cc.getMasterConfig).map(getMembers).getOrElse(Seq.empty) ++
    Option(cc.getWorkerConfig).map(getMembers).getOrElse(Seq.empty) ++
    Option(cc.getSecondaryWorkerConfig).map(getMembers).getOrElse(Seq.empty)
  ).toSet

  def handle(dir: String,
             projectId: String,
             zone: String,
             maxAgeSeconds: Long,
             audience: String)
            (implicit sys: ActorSystem, mat: Materializer, ctx: ExecutionContext): Route = {
    decodeRequestWith(Gzip,NoCoding){
      encodeResponseWith(Gzip,NoCoding){
        extractClientIP{ip =>
          entity(as[String]){tokenEnc =>
            val id = EnhancedIdToken(tokenEnc)
            if (!id.verify(audience))
              reject(ValidationRejection(s"invalid audience '$audience'"))
            else if (id.age > maxAgeSeconds)
              reject(ValidationRejection(s"age ${id.age} exceeds $maxAgeSeconds"))
            else {
              val instance = ApiQuery.getInstance(projectId, zone, id.instanceName)
              if (instance.isEmpty) {
                reject(ValidationRejection(s"unable to find instance '${id.instanceName}'"))
              } else {
                if (!hasIp(ip, instance.get)){
                  reject(ValidationRejection(s"'${id.instanceName}' doesn't have ip $ip"))
                } else {
                  val clusterName = instance.get.getMetadata.getItems.asScala
                    .find(_.getKey == ApiQuery.ClusterLabel).map(_.getValue)
                  if (clusterName.isDefined){
                    val clusterFilter = ClusterFilter(clusterName = clusterName.get)
                    val cluster = ApiQuery.listClusters(zone.dropRight(2), id.projectId,
                      clusterFilter, new ArrayBuffer[Cluster](1)).headOption
                    if (cluster.isDefined){
                      val instances: Set[String] = getMembers(cluster.get.getConfig)
                      if (instances.contains(id.instanceName)) {
                        extractUnmatchedPath {unmatchedPath =>
                          val path = unmatchedPath.toString
                          val basedir = Paths.get(dir).toAbsolutePath
                          if (path.contains(".."))
                            reject(ValidationRejection(s"bad path $path"))
                          else {
                            val f = basedir.resolve(path.stripPrefix("/")).toAbsolutePath
                            if (f.toString.length > basedir.toString.length &&
                              Files.isRegularFile(f)){
                              complete(HttpEntity.Default(
                                MediaTypes.`application/octet-stream`,
                                f.toFile.length,
                                FileIO.fromPath(f)))
                            } else reject(ValidationRejection(s"invalid path $path"))
                          }
                        }
                      } else reject(ValidationRejection(
                        s"cluster '${clusterName.get}' does not have member '${id.instanceName}'"))
                    } else reject(ValidationRejection(s"unable to find cluster '${clusterName.get}'"))
                  } else reject(ValidationRejection(s"unable to find key '${ApiQuery.ClusterLabel}'"))
                }
              }
            }
          }
        }
      }
    }
  }
}
