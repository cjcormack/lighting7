package uk.me.cormack.lighting7.trackServer

import com.google.protobuf.Empty
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.flow.*
import uk.me.cormack.lighting7.grpc.*
import uk.me.cormack.lighting7.show.Show

class TrackServer(
    val port: Int,
    private val show: Show,
) {
    val server: Server =
        ServerBuilder.forPort(port)
            .addService(TrackDetailsService(show)).build()

    fun start() {
        server.start()
        println("Server started, listening on $port")
        Runtime.getRuntime().addShutdownHook(
            Thread {
                println("*** shutting down gRPC server since JVM is shutting down")
                this@TrackServer.stop()
                println("*** server shut down")
            },
        )
    }
    fun stop() {
        server.shutdown()
    }

    fun blockUntilShutdown() {
        server.awaitTermination()
    }
}

class TrackDetailsService(private val show: Show) : TrackNotifyGrpcKt.TrackNotifyCoroutineImplBase() {
    override fun playerStateNotifier(request: Empty): Flow<TrackState> {
        return show.trackStateFlow
    }

    override suspend fun notifyCurrentTrack(request: TrackDetails): Empty {
        show.trackChanged(request)

        return Empty.getDefaultInstance()
    }
}
