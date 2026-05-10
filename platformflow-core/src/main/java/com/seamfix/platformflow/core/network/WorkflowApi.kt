package com.seamfix.platformflow.core.network

import com.seamfix.platformflow.core.api.SessionResult
import com.seamfix.platformflow.core.model.WorkflowDefinition
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit-generated wire interface for the PlatformFlow control plane.
 *
 * Endpoint shapes are stable; implementation lives in the auto-generated
 * Retrofit proxy. Custom Gson adapters registered via
 * [com.seamfix.platformflow.core.model.WorkflowJson.gson] handle
 * [WorkflowDefinition]'s wire form (Comparator tokens, RuleItem
 * discriminator, etc.).
 *
 * The interface is `internal`-flavored — host apps consume the
 * higher-level [WorkflowClient] interface, not this one. Kept `public`
 * (default) so `Retrofit.create(WorkflowApi::class.java)` can synthesize
 * the proxy.
 */
interface WorkflowApi {

    /** GET /workflows/{id} → workflow definition JSON. */
    @GET("workflows/{id}")
    suspend fun fetchWorkflow(@Path("id") workflowId: String): WorkflowDefinition

    /** POST /results — body is the session result. */
    @POST("results")
    suspend fun reportResult(@Body result: SessionResult)
}
