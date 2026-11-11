// Family TV source connector, protocol 1. See docs/CONNECTOR.md.
//
// FROZEN: once released, never rename, reorder, renumber or change a method's parameters here.
// A new method takes the next free number; anything else is a new package (v2) with its own action.
package app.familytv.connector.v1;

import android.os.Bundle;
import app.familytv.connector.v1.ISourceCallback;

/**
 * Bound by Family TV (the host) on a companion app's service. Every call is one-way: the answer
 * comes back on [callback] with the same requestId, as onResult or onError, exactly once.
 */
oneway interface ISourceProvider {
    /** hello: protocolMin, protocolMax, hostVersion, capabilities. Result: protocol, capabilities, providerName. */
    void negotiate(String requestId, in Bundle hello, ISourceCallback callback) = 1;

    /** identity: type, tmdb, imdb, tvdb, season, episode. Result: sources (a list of source Bundles). */
    void listSources(String requestId, String sessionId, in Bundle identity, ISourceCallback callback) = 2;

    /** selectionId: one of the ids listSources returned in this session. Result: uri, mimeType, headers, expiresAtMs. */
    void resolve(String requestId, String sessionId, String selectionId, ISourceCallback callback) = 3;

    /** The host no longer wants requestId's answer. The provider may answer CANCELLED or not at all. */
    void cancel(String requestId) = 4;

    /** The host is done with sessionId: its selection ids stop working. */
    void closeSession(String sessionId) = 5;
}
