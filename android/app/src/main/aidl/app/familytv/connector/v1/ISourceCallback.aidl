// Family TV source connector, protocol 1. See docs/CONNECTOR.md. FROZEN, like ISourceProvider.
package app.familytv.connector.v1;

import android.os.Bundle;

/** Implemented by the host. The provider calls exactly one of these per request. */
oneway interface ISourceCallback {
    void onResult(String requestId, in Bundle result) = 1;

    /** code: 1 UNSUPPORTED_VERSION, 2 NOT_ENTITLED, 3 NOT_FOUND, 4 TIMEOUT, 5 PROVIDER_ERROR, 6 CANCELLED. detail is never shown or logged. */
    void onError(String requestId, int code, String detail) = 2;
}
