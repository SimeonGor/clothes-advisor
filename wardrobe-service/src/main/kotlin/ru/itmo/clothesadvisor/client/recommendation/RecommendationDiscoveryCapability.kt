package ru.itmo.clothesadvisor.client.recommendation

import feign.Capability
import feign.Client
import java.io.IOException
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient

class RecommendationDiscoveryCapability : Capability {
    override fun enrich(client: Client): Client {
        if (client !is FeignBlockingLoadBalancerClient) return client

        return Client { request, options ->
            val response = client.execute(request, options)
            // The load balancer keeps the original request only when no instance was selected.
            // Network responses carry a new request until Feign replaces it after execute returns.
            if (response.status() == 503 && response.request() === request) {
                response.body()?.close()
                throw IOException("Recommendation service is unavailable")
            }
            response
        }
    }
}
