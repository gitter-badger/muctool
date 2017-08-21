/*
 * MUCtool Web Toolkit
 *
 * Copyright 2017 Alexander Orlov <alexander.orlov@loxal.net>. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.loxal.muctool

import com.fasterxml.jackson.databind.ObjectMapper
import com.maxmind.db.CHMCache
import com.maxmind.geoip2.DatabaseReader
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.PersistentEntityStores
import jetbrains.exodus.entitystore.StoreTransaction
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.ktor.application.Application
import org.jetbrains.ktor.application.install
import org.jetbrains.ktor.auth.*
import org.jetbrains.ktor.content.default
import org.jetbrains.ktor.content.files
import org.jetbrains.ktor.content.static
import org.jetbrains.ktor.features.CallLogging
import org.jetbrains.ktor.features.Compression
import org.jetbrains.ktor.features.DefaultHeaders
import org.jetbrains.ktor.gson.GsonSupport
import org.jetbrains.ktor.http.ContentType
import org.jetbrains.ktor.http.HttpStatusCode
import org.jetbrains.ktor.http.withCharset
import org.jetbrains.ktor.locations.Locations
import org.jetbrains.ktor.locations.location
import org.jetbrains.ktor.pipeline.PipelineContext
import org.jetbrains.ktor.request.receiveText
import org.jetbrains.ktor.response.respond
import org.jetbrains.ktor.response.respondText
import org.jetbrains.ktor.routing.*
import org.jetbrains.ktor.util.decodeBase64
import org.jetbrains.ktor.util.toMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.*
import java.util.concurrent.atomic.AtomicLong

private val LOG: Logger = LoggerFactory.getLogger(Application::class.java)
val dilbertService = "http://sky.loxal.net:1181"
private val RESOURCES = "src/main/resources/"
private val mapper = ObjectMapper()

private val asnDBreader: DatabaseReader = DatabaseReader
        .Builder(File("${RESOURCES}GeoLite2-ASN.mmdb"))
        .withCache(CHMCache())
        .build()

private val cityDBreader: DatabaseReader = DatabaseReader
        .Builder(File("${RESOURCES}GeoLite2-City.mmdb"))
        .withCache(CHMCache())
        .build()

private val countryDBreader: DatabaseReader = DatabaseReader
        .Builder(File("${RESOURCES}GeoLite2-Country.mmdb"))
        .withCache(CHMCache())
        .build()

private val pageViews: AtomicLong = AtomicLong()
private val whoisPerClient: MutableMap<UUID, Long> = mutableMapOf()

private suspend fun PipelineContext<Unit>.inetAddress(): InetAddress? {
    val queryIP = call.request.queryParameters["queryIP"]
    if (queryIP != null) LOG.info("queryIP: $queryIP")

    try {
        return if (queryIP == null) InetAddress.getByName(call.request.local.remoteHost) else InetAddress.getByName(queryIP)
    } catch (e: UnknownHostException) {
        LOG.info(e.message)
        return null
    }
}

@location("/admin")
class Admin

@location("/user")
class User

@location("/stats")

val hashedUsers = UserHashedTableAuth(table = mapOf(
        "test" to decodeBase64("VltM4nfheqcJSyH887H+4NEOm2tDuKCl83p5axYXlF0=")
))

fun Application.main() {
    install(Locations)
    install(Compression)
    install(DefaultHeaders)  // TODO add correlation UUID to trace calls in logs
    install(GsonSupport)
    install(CallLogging)
//    install(CORS) {
//        // breaks font-awesome, when used in plain form
//        method(HttpMethod.Options)
//        method(HttpMethod.Get)
//        header(HttpHeaders.XForwardedProto)
//        header(HttpHeaders.Referrer)
//        anyHost()
//        allowCredentials = true
//        maxAge = Duration.ofDays(1)
//    }
    routing {
        location<User> {
            authentication {
                basicAuthentication("muctool") { hashedUsers.authenticate(it) }
            }

            get {
                call.respondText("Success, ${call.principal<UserIdPrincipal>()?.name}")
            }
        }
        location<Admin> {
            authentication {
                basicAuthentication("muctool") { credentials ->
                    if (credentials.name == credentials.password) {
                        UserIdPrincipal(credentials.name)
                    } else {
                        null
                    }
                }
            }

            get {
                call.respondText("Success, ${call.principal<UserIdPrincipal>()?.name}")
            }
        }
        get {
            LOG.info("pageViews: ${pageViews.incrementAndGet()}")
        }
//        options("dilbert-quote/{path}") {
//            LOG.info("remoteHost: ${call.request.local.remoteHost}")
//            call.respondRedirect("$dilbertService/dilbert-quote/${call.parameters["path"]}", true)
//        }
//        get("dilbert-quote/{path}") {
//            call.respondRedirect("$dilbertService/dilbert-quote/${call.parameters["path"]}", true)
//        }
        get("whois/asn") {
            val ip: InetAddress? = inetAddress()
            asnDBreader.let({ reader ->
                try {
                    val dbLookup = reader.asn(ip)
                    call.respondText(dbLookup.toJson(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.NotFound)
                }
            })
        }
        get("whois") {
            val clientId: UUID
            try {
                val clientIdParam = call.request.queryParameters["clientId"]
                clientId = if (clientIdParam == null)
                    UUID.fromString("0-0-0-0-0")
                else
                    UUID.fromString(clientIdParam)

                LOG.info("clientId: $clientId")
            } catch (e: Exception) {
                call.respondText(
                        "clientId query parameter must be a valid UUID",
                        ContentType.Text.Plain,
                        HttpStatusCode.BadRequest
                )
                return@get
            }

            val ip: InetAddress? = inetAddress()
            cityDBreader.let({ reader ->
                try {
                    val dbLookupMajor = reader.city(ip)

                    var isp: String = ""
                    var ispId: Int = -1

                    asnDBreader.let({ readerAsn ->
                        val dbLookupMinor = readerAsn.asn(ip)
                        isp = dbLookupMinor.autonomousSystemOrganization
                        ispId = dbLookupMinor.autonomousSystemNumber
                    })

                    val whois = Whois(
                            ip = InetAddress.getByName(dbLookupMajor.traits.ipAddress),
                            country = dbLookupMajor.country.name ?: "",
                            countryIso = dbLookupMajor.country.isoCode ?: "",
                            countryGeonameId = dbLookupMajor.country.geoNameId ?: -1,
                            isp = isp,
                            ispId = ispId,
                            city = dbLookupMajor.city.name ?: "",
                            cityGonameId = dbLookupMajor.city.geoNameId ?: -1,
                            isTor = false,
                            timeZone = dbLookupMajor.location.timeZone ?: "",
                            latitude = dbLookupMajor.location.latitude ?: -1.0,
                            longitude = dbLookupMajor.location.longitude ?: -1.0,
                            postalCode = dbLookupMajor.postal.code ?: "",
                            subdivisionGeonameId = dbLookupMajor.mostSpecificSubdivision.geoNameId ?: -1,
                            subdivisionIso = dbLookupMajor.mostSpecificSubdivision.isoCode ?: ""
                    )

                    call.respondText(mapper.writeValueAsString(whois), ContentType.Application.Json)
                    whoisPerClient.put(clientId, whoisPerClient.getOrDefault(clientId, 0).inc())
                } catch (e: Exception) {
                    LOG.info(e.message)
                    call.respond(HttpStatusCode.NotFound)
                }
            })
        }

        get("whois/city") {
            val ip: InetAddress? = inetAddress()
            cityDBreader.also({ reader ->
                try {
                    val dbLookup = reader.city(ip)
                    call.respondText(dbLookup.toJson(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.NotFound)
                }
            })
        }
        get("whois/country") {
            val ip: InetAddress? = inetAddress()
            countryDBreader.also({ reader ->
                try {
                    val dbLookup = reader.country(ip)
                    call.respondText(dbLookup.toJson(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.NotFound)
                }
            })
        }
        get("echo") {
            call.respond(echo())
        }
        post("echo") {
            call.respond(echo())
        }
        put("echo") {
            call.respond(echo())
        }
        delete("echo") {
            call.respond(echo())
        }
        get("entropy") {
            call.respondText(UUID.randomUUID().toString(), ContentType.Application.Json)
        }
        get("randomness") {
            call.respond(Randomness())
        }
        get("test") {
            val entityStore = PersistentEntityStores.newInstance("./data")
            entityStore.executeInTransaction({ txn: StoreTransaction ->
                val message: Entity = txn.newEntity("Message")
                message.setProperty("Hello", "World!")
            })
            entityStore.close()
            call.respondText("triggered", ContentType.Text.Plain)
        }
        val uptimeChecks: MutableMap<UUID, TimerTask> = mutableMapOf()
        get("uptime") {
            // TODO register callback URL for notification
            val monitorUrl: URI = if (call.request.queryParameters.contains("url"))
                URI.create(call.request.queryParameters["url"]) else URI.create("https://example.com")

            class TestTimerTask(val monitor: URI) : TimerTask() {
                val client = OkHttpClient()

                @Throws(IOException::class)
                fun run(url: String): String {
                    val request = Request.Builder()
                            .url(url)
                            .build()

                    val response = client.newCall(request).execute()
                    LOG.info("response.code(): ${response.code()}")
                    return response.body()!!.string()
                }

                override fun run() {
                    val content: String = run("https://example.com")
                    LOG.info("content: $content")
                    // call monitor via client
                    LOG.info("$monitor")
                    LOG.info("`{uptimeChecks}`: ${uptimeChecks.size}")
                }
            }

            val uptimeCheck = TestTimerTask(monitorUrl)

            Timer().schedule(uptimeCheck, 0, 6_000)
            uptimeChecks.put(UUID.randomUUID(), uptimeCheck)
            call.respondText("{\"registered\": true}", ContentType.Application.Json)
        }
        get("scan") {
            val scanUrl: URI = if (call.request.queryParameters.contains("url"))
                URI.create(call.request.queryParameters["url"]) else URI.create("https://www.sitemaps.org")
            LOG.info("scanUrl: ${scanUrl}")

            val client = OkHttpClient()
            val request = Request.Builder()
                    .url(scanUrl.toURL())
                    .build()

            val response = client.newCall(request).execute()
            LOG.info("response.code(): ${response.code()}")

            call.respondText("{\"scanned\": true}", ContentType.Application.Json)
        }
        get("stats") {
            // TODO protect with basic auth
            val stats = Stats(
                    pageViews = pageViews.toLong(),
                    whoisPerClient = whoisPerClient,
                    scmHash = System.getenv("SCM_HASH") ?: "",
                    buildNumber = System.getenv("BUILD_NUMBER") ?: ""
            )

            call.respondText(mapper.writeValueAsString(stats), ContentType.Application.Json)
        }
        static("/") {
            files("static")
            default("static/main.html")
        }
    }
}

private suspend fun PipelineContext<Unit>.echo(): Echo {
    return Echo(
            data = call.receiveText(),
            method = call.request.local.method.value,
            version = call.request.local.version,
            scheme = call.request.local.scheme,
            uri = call.request.local.uri,
            query = call.request.queryParameters.toMap(),
            headers = call.request.headers.toMap(),
            ip = call.request.local.remoteHost,
            host = call.request.local.host
    )
}
