import { restApi } from "./restApi"

export interface UniversePacketStats {
  subnet: number
  universe: number
  packetsPerSec: number
  totalPackets: number
}

export interface ArtNetRatesResponse {
  windowSeconds: number
  universes: UniversePacketStats[]
}

export interface BucketCount {
  upperBoundNanos: number
  count: number
}

export interface LatencyHistogramSnapshot {
  count: number
  sumNanos: number
  maxNanos: number
  meanNanos: number
  p50Nanos: number
  p95Nanos: number
  p99Nanos: number
  buckets: BucketCount[]
}

export interface PortCcRates {
  displayKey: string
  displayName: string
  inboundCcPerSec: number
  inboundCcTotal: number
  outboundCcPerSec: number
  outboundCcTotal: number
}

export interface MidiLatencySnapshot {
  buckets: Record<string, LatencyHistogramSnapshot>
}

export interface MidiLatencyResponse {
  windowSeconds: number
  histograms: MidiLatencySnapshot
  ports: PortCcRates[]
}

export const perfApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    getArtNetRates: build.query<ArtNetRatesResponse, void>({
      query: () => 'perf/artnet-rates',
    }),
    getMidiLatency: build.query<MidiLatencyResponse, void>({
      query: () => 'perf/midi-latency',
      providesTags: ['PerfMidi'],
    }),
    resetMidiLatency: build.mutation<void, void>({
      query: () => ({
        url: 'perf/midi-latency/reset',
        method: 'POST',
      }),
      invalidatesTags: ['PerfMidi'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useGetArtNetRatesQuery,
  useGetMidiLatencyQuery,
  useResetMidiLatencyMutation,
} = perfApi
