package de.uksh.medic.cxx2medic.fhir.interception

import ca.uhn.fhir.interceptor.api.Hook
import ca.uhn.fhir.interceptor.api.Interceptor
import ca.uhn.fhir.interceptor.api.Pointcut
import ca.uhn.fhir.rest.client.api.IHttpRequest

abstract class HeaderEnrichingInterceptor<P: HeadersProvider>
{
    abstract val provider: P

    @Hook(Pointcut.CLIENT_REQUEST)
    fun enrichHeader(request: IHttpRequest)
    {
        provider.getHeaders().forEach { e -> e.value.forEach { request.addHeader(e.key, it) } }
    }
}

fun interface HeadersProvider
{
    fun getHeaders(): Map<String, List<String>>
}