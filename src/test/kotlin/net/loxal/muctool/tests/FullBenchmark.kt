/*
 * Copyright 2017 Alexander Orlov <alexander.orlov@loxal.net>. All rights reserved.
 */

package net.loxal.muctool.tests

import org.jetbrains.ktor.content.LocalFileContent
import org.jetbrains.ktor.content.resolveResource
import org.jetbrains.ktor.http.HttpMethod
import org.jetbrains.ktor.http.HttpStatusCode
import org.jetbrains.ktor.response.respond
import org.jetbrains.ktor.routing.get
import org.jetbrains.ktor.routing.routing
import org.jetbrains.ktor.testing.TestApplicationCall
import org.jetbrains.ktor.testing.TestApplicationHost
import org.jetbrains.ktor.testing.createTestEnvironment
import org.jetbrains.ktor.testing.handleRequest
import org.openjdk.jmh.annotations.*
import java.io.File
import java.util.concurrent.TimeUnit


@State(Scope.Benchmark)
open class FullBenchmark {
    private val testHost: TestApplicationHost = TestApplicationHost(createTestEnvironment())
    private val classSignature = listOf(0xca, 0xfe, 0xba, 0xbe).map(Int::toByte)
    private val packageName = FullBenchmark::class.java.`package`.name
    private val classFileName = FullBenchmark::class.simpleName!! + ".class"
    private val pomFile = File("pom.xml")

    @Setup
    fun startServer() {
        testHost.start()
        testHost.application.routing {
            get("/sayOK") {
                call.respond("OK")
            }
            get("/jarfile") {
                val resource = call.resolveResource("String.class", "java.lang")!!
                call.respond(resource)
            }
            get("/regularClasspathFile") {
                val resource = call.resolveResource(classFileName, packageName)!!
                call.respond(resource)
            }
            get("/regularFile") {
                call.respond(LocalFileContent(pomFile))
            }
        }
    }

    @TearDown
    fun stopServer() {
        testHost.stop(0L, 0L, TimeUnit.MILLISECONDS)
    }

    @Benchmark
    fun sayOK() = handle("/sayOK") {
        if (response.content != "OK") {
            throw IllegalStateException()
        }
    }

    @Benchmark
    fun sayClasspathResourceFromJar() = handle("/jarfile") {
        if (response.byteContent!!.take(4) != classSignature) {
            throw IllegalStateException("Wrong class signature")
        }
    }

    @Benchmark
    fun sayClasspathResourceRegular() = handle("/regularClasspathFile") {
        if (response.byteContent!!.take(4) != classSignature) {
            throw IllegalStateException("Wrong class signature")
        }
    }

    @Benchmark
    fun sayRegularFile() = handle("/regularFile") {
        response.byteContent
    }

    private inline fun <R> handle(url: String, block: TestApplicationCall.() -> R) = testHost.handleRequest(HttpMethod.Get, url).apply {
        if (response.status() != HttpStatusCode.OK) {
            throw IllegalStateException("Expected 'HttpStatusCode.OK' but got '${response.status()}'")
        }

        block()
    }
}

/*
FullBenchmark.sayClasspathResourceFromJar  thrpt   10   23.610 ± 1.580  ops/ms
FullBenchmark.sayClasspathResourceRegular  thrpt   10   15.055 ± 0.215  ops/ms
FullBenchmark.sayOK                        thrpt   10  138.655 ± 2.271  ops/ms
FullBenchmark.sayRegularFile               thrpt   10   24.001 ± 0.254  ops/ms
 */

fun main(args: Array<String>) {
    benchmark(args) {
        run<FullBenchmark>()
    }
}