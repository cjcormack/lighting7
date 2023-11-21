package uk.me.cormack.lighting7

import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dao.DatabaseFactory
import uk.me.cormack.lighting7.models.Script
import uk.me.cormack.lighting7.models.Scripts
import uk.me.cormack.lighting7.plugins.*
import uk.me.cormack.lighting7.show.Show

fun main(args: Array<String>): Unit {
    val argsWithDefaults = if (args.any { it.startsWith("-config=") }) {
        args
    } else {
        args + "-config=application.conf" + "-config=local.conf"
    }
    EngineMain.main(argsWithDefaults)
}

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
fun Application.module() {
    configureHTTP()
    DatabaseFactory.init(environment.config)
    configureSockets()
    configureRouting()

//    transaction {
//        val foundScript = Script.find {
//            Scripts.name eq "init"
//        }
//
//        foundScript.forEach {
//            println(it.script)
//        }
//    }

    launch {
        Show.start()
    }

//    Fixtures.hex1.rgbColor = Color.GREEN
//    Fixtures.hex1.level = 255u
//    Fixtures.hex2.rgbColor = Color.BLUE
//    Fixtures.hex2.level = 255u
//
//    repeat(5) {
//        Thread.sleep(5000)
//        Fixtures.hex1.level = 0u
//        Fixtures.hex2.level = 0u
//        Thread.sleep(50)
//        Fixtures.hex1.level = 255u
//        Fixtures.hex2.level = 255u
//        Thread.sleep(1000)
//        Fixtures.hex1.level = 0u
//        Fixtures.hex2.level = 0u
//        Thread.sleep(75)
//        Fixtures.hex1.level = 255u
//        Fixtures.hex2.level = 255u
//        Thread.sleep(200)
//        Fixtures.hex1.level = 0u
//        Fixtures.hex2.level = 0u
//        Thread.sleep(75)
//        Fixtures.hex1.level = 255u
//        Fixtures.hex2.level = 255u
//    }
}
