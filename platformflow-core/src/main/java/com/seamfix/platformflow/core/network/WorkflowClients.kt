package com.seamfix.platformflow.core.network

import com.google.gson.Gson
import com.seamfix.platformflow.core.model.WorkflowJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Factory functions for [WorkflowClient] implementations.
 *
 * Production callers use [retrofit] to build a fully-wired client
 * pointing at the configured base URL. Tests typically construct
 * [RetrofitWorkflowClient] directly with a fake `WorkflowApi`.
 */
object WorkflowClients {

    /**
     * Build a Retrofit-backed [WorkflowClient] pointed at [baseUrl].
     *
     * @param baseUrl Server root, e.g. `"https://api.platformflow.io/"`
     *  (must end with `/`).
     * @param cacheConfig Cache tuning. Defaults to 24-hour TTL, 10-entry
     *  LRU per §14.2.
     * @param gson Gson instance with the workflow-JSON adapters
     *  registered. Defaults to [WorkflowJson.gson].
     * @param backgroundScope Where stale-while-revalidate refreshes
     *  run. Defaults to a fresh `SupervisorJob` on `Dispatchers.IO` —
     *  appropriate for application-scoped clients. Pass a
     *  lifecycle-aware scope if the client should be torn down with
     *  some shorter scope (e.g. an Activity).
     */
    fun retrofit(
        baseUrl: String,
        cacheConfig: WorkflowCacheConfig = WorkflowCacheConfig(),
        gson: Gson = WorkflowJson.gson,
        backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ): WorkflowClient {
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        val api = retrofit.create(WorkflowApi::class.java)
        return RetrofitWorkflowClient(
            api = api,
            cache = WorkflowCache(cacheConfig),
            backgroundScope = backgroundScope,
        )
    }
}
