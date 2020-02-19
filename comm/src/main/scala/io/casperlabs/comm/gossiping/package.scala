package io.casperlabs.comm

import io.casperlabs.metrics.Metrics

package object gossiping {
  val GossipingMetricsSource        = Metrics.Source(CommMetricsSource, "gossiping")
  val BlocksGossipingMetricsSource  = Metrics.Source(GossipingMetricsSource, "blocks")
  val DeploysGossipingMetricsSource = Metrics.Source(GossipingMetricsSource, "deploys")

  /** Alias for return type of methods that return a handle that can be waited upon. */
  type WaitHandle[F[_]] = F[Unit]
}
