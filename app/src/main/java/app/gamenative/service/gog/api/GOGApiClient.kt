package app.gamenative.service.gog.api

import android.content.Context
import app.gamenative.service.gog.GOGAuthManager
import app.gamenative.utils.Net
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Native Kotlin API client for GOG Content System
 *
 * Replaces Python GOGDL API calls with direct HTTP requests
 */
@Singleton
class GOGApiClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: GOGManifestParser,
) {

    companion object {
        private const val GOG_CONTENT_SYSTEM = "https://content-system.gog.com"
        private const val GOG_CDN = "https://gog-cdn-fastly.gog.com"
    }

    private val httpClient = Net.http

    // TODO: Compose any functions to reduce DRYNESS.

    /**
     * Get available builds for a game.
     *
     * @param gameId GOG product ID
     * @param platform Target platform (e.g. "windows", "linux", "osx")
     * @param generation 1 = legacy, 2 = modern; only builds of this generation are returned
     */
    suspend fun getBuildsForGame(
        gameId: String,
        platform: String = "windows",
        generation: Int = 2,
    ): Result<BuildsResponse> =
        withContext(Dispatchers.IO) {
            try {
                val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
                if (credentials == null) {
                    return@withContext Result.failure(Exception("Not authenticated"))
                }

                val url = "$GOG_CONTENT_SYSTEM/products/$gameId/os/$platform/builds?generation=$generation"

                Timber.tag("GOG").d("Fetching builds from: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch builds: HTTP ${response.code}"),
                    )
                }

                val jsonStr = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                val buildsResponse = parser.parseBuilds(jsonStr)

                Timber.tag("GOG").d("Found ${buildsResponse.totalCount} build(s) for game $gameId (gen $generation)")

                Result.success(buildsResponse)
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to get builds for game $gameId")
                Result.failure(e)
            }
        }

    suspend fun fetchDependencyRepository(url: String): Result<DependencyRepository> = withContext(Dispatchers.IO){
        try {
            val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()

            if (credentials == null) {
                return@withContext Result.failure(Exception("Not authenticated"))
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to fetch manifest: HTTP ${response.code}"),
                )
            }

            val jsonStr = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response from dependency repository"))

            val json = JSONObject(jsonStr)

            val dependencyRepositoryDetails = DependencyRepository.fromJson(json)
            Result.success(dependencyRepositoryDetails)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to fetch dependency repository from $url")
            Result.failure(e)
        }
    }

    /**
     * Get open link for dependencies (doesn't require product ID)
     *
     * @return List of CDN URLs for dependencies
     */
    suspend fun getDependencyOpenLink(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
            if (credentials == null) {
                return@withContext Result.failure(Exception("Not authenticated"))
            }

            val url = "$GOG_CONTENT_SYSTEM/open_link?generation=2&_version=2&path=/dependencies/store/"

            Timber.tag("GOG").d("Getting dependency open link")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to get dependency open link: HTTP ${response.code}"),
                )
            }

            val jsonStr = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            val json = JSONObject(jsonStr)
            val urlsArray = json.optJSONArray("urls")
            val urls = mutableListOf<String>()

            if (urlsArray != null) {
                for (i in 0 until urlsArray.length()) {
                    val urlObj = urlsArray.optJSONObject(i)
                    if (urlObj != null) {
                        val urlFormat = urlObj.optString("url_format", "")
                        val paramsObj = urlObj.optJSONObject("parameters")

                        if (urlFormat.isNotEmpty() && paramsObj != null) {
                            var constructedUrl = urlFormat
                            val keys = paramsObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = paramsObj.get(key).toString()
                                constructedUrl = constructedUrl.replace("{$key}", value)
                            }
                            constructedUrl = constructedUrl.replace("\\/", "/")
                            if (constructedUrl.isNotEmpty()) {
                                urls.add(constructedUrl)
                            }
                        }
                    }
                }
            }

            Timber.tag("GOG").d("Got ${urls.size} dependency URL(s)")
            Result.success(urls)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to get dependency open link")
            Result.failure(e)
        }
    }

    suspend fun fetchDependencyManifest(manifestUrl: String): Result<GOGDependencyManifestMeta> = withContext(Dispatchers.IO) {
        try {
                val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
                if (credentials == null) {
                    return@withContext Result.failure(Exception("Not authenticated"))
                }

                val request = Request.Builder()
                    .url(manifestUrl)
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch manifest: HTTP ${response.code}"),
                    )
                }

                val manifestBytes = response.body?.bytes()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                // Decompress based on detected format
                val manifestStr = parser.decompressManifest(manifestBytes)

                Timber.tag("GOG").d("Manifest decompressed, size: ${manifestStr.length} bytes")

                val manifest = parser.parseDependencyManifest(manifestStr)

                Result.success(manifest)
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to fetch dependency manifest from $manifestUrl")
                Result.failure(e)
            }
    }

    /**
     * Fetch build manifest (zlib or gzip compressed JSON)
     *
     * @param manifestUrl URL from build.link field
     * @return Parsed manifest data
     */
    suspend fun fetchManifest(manifestUrl: String): Result<GOGManifestMeta> =
        withContext(Dispatchers.IO) {
            try {
                val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
                if (credentials == null) {
                    return@withContext Result.failure(Exception("Not authenticated"))
                }

                Timber.tag("GOG").d("Fetching manifest from: $manifestUrl")

                val request = Request.Builder()
                    .url(manifestUrl)
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch manifest: HTTP ${response.code}"),
                    )
                }

                val manifestBytes = response.body?.bytes()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                // Decompress based on detected format
                val manifestStr = parser.decompressManifest(manifestBytes)

                Timber.tag("GOG").d("Manifest decompressed, size: ${manifestStr.length} bytes")

                val manifest = parser.parseManifest(manifestStr)

                Timber.tag("GOG").i(
                    "Manifest parsed: ${manifest.installDirectory}, ${manifest.depots.size} depot(s)",
                )

                Result.success(manifest)
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to fetch manifest from $manifestUrl")
                Result.failure(e)
            }
        }

    /**
     * Fetch Gen 1 (v1) depot manifest (plain JSON, depot.files format).
     * URL: GOG_CDN/content-system/v1/manifests/{productId}/{platform}/{timestamp}/{manifestHash}
     */
    suspend fun fetchDepotManifestV1(
        productId: String,
        platform: String,
        timestamp: String,
        manifestHash: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
            if (credentials == null) {
                return@withContext Result.failure(Exception("Not authenticated"))
            }
            val url = "$GOG_CDN/content-system/v1/manifests/$productId/$platform/$timestamp/$manifestHash"
            Timber.tag("GOG").d("Fetching Gen 1 depot manifest: $url")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to fetch Gen 1 depot manifest: HTTP ${response.code}"))
            }
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            Result.success(body)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to fetch Gen 1 depot manifest")
            Result.failure(e)
        }
    }

    /**
     * Fetch depot manifest (Gen 2) and return the raw inflated JSON alongside the parsed form.
     * The native GOG engine (GameDownloadService → libgndownload store_dl/gog) re-parses the
     * raw JSON itself, so the byte-exact string must survive the trip.
     */
    suspend fun fetchDepotManifestWithRaw(manifestHash: String): Result<Pair<DepotManifest, String>> =
        withContext(Dispatchers.IO) {
            try {
                val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
                    ?: return@withContext Result.failure(Exception("Not authenticated"))

                val path = gogGalaxyPath(manifestHash)
                val url = "$GOG_CDN/content-system/v2/meta/$path"

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch depot manifest: HTTP ${response.code}"),
                    )
                }

                val depotBytes = response.body?.bytes()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                val depotStr = parser.decompressManifest(depotBytes)
                Result.success(parser.parseDepotManifest(depotStr) to depotStr)
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to fetch depot manifest $manifestHash")
                Result.failure(e)
            }
        }

    /**
     * Fetch depot manifest (contains file list for a specific depot) — Gen 2
     *
     * @param manifestHash Hash from depot.manifest field
     * @return Parsed depot manifest
     */
    suspend fun fetchDepotManifest(manifestHash: String): Result<DepotManifest> =
        withContext(Dispatchers.IO) {
            try {
                val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
                if (credentials == null) {
                    return@withContext Result.failure(Exception("Not authenticated"))
                }

                // Build depot manifest URL
                val path = gogGalaxyPath(manifestHash)
                val url = "$GOG_CDN/content-system/v2/meta/$path"

                Timber.tag("GOG").d("Fetching depot manifest: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch depot manifest: HTTP ${response.code}"),
                    )
                }

                val depotBytes = response.body?.bytes()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                // Depot manifests are also compressed
                val depotStr = parser.decompressManifest(depotBytes)

                val depotManifest = parser.parseDepotManifest(depotStr)

                Timber.tag("GOG").d(
                    "Depot manifest parsed: ${depotManifest.files.size} file(s), " +
                        "${depotManifest.directories.size} dir(s)",
                )

                Result.success(depotManifest)
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "Failed to fetch depot manifest $manifestHash")
                Result.failure(e)
            }
        }

    /**
     * Fetch dependency depot manifest using open link CDN URLs
     * Dependencies don't require authentication per-file, they use open links
     * Note: Manifests use /dependencies/meta/ path, not /dependencies/store/
     *
     * @param manifestHash Hash from depot.manifest field
     * @param baseUrls List of open link CDN URLs (unused, we use hardcoded CDN)
     * @return Parsed depot manifest
     */
    suspend fun fetchDependencyDepotManifest(
        manifestHash: String,
        baseUrls: List<String>
    ): Result<DepotManifest> = withContext(Dispatchers.IO) {
        try {
            // Build depot manifest URL
            // Dependency manifests use /dependencies/meta/ path on the CDN
            val path = gogGalaxyPath(manifestHash)
            val url = "$GOG_CDN/content-system/v2/dependencies/meta/$path"

            Timber.tag("GOG").d("Fetching dependency depot manifest: $url")

            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to fetch dependency depot manifest: HTTP ${response.code}"),
                )
            }

            val depotBytes = response.body?.bytes()
                ?: return@withContext Result.failure(Exception("Empty response"))

            // Depot manifests are compressed
            val depotStr = parser.decompressManifest(depotBytes)

            val depotManifest = parser.parseDepotManifest(depotStr)

            Timber.tag("GOG").d(
                "Dependency depot manifest parsed: ${depotManifest.files.size} file(s), " +
                    "${depotManifest.directories.size} dir(s)",
            )

            Result.success(depotManifest)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to fetch dependency depot manifest $manifestHash")
            Result.failure(e)
        }
    }

    /**
     * Get secure download links for a product
     *
     * These are time-limited CDN URLs that work for all chunks in the product
     * No need to pass chunk hashes - the URLs work for any chunk
     *
     * @param productId Game or DLC product ID
     * @param path Path prefix (usually "/" for gen 2)
     * @param generation API generation (1 or 2)
     * @param root Optional root path (e.g., "/patches/store" for patches)
     * @return List of secure CDN URLs
     */
    suspend fun getSecureLink(
        productId: String,
        path: String = "/",
        generation: Int = 2,
        root: String? = null,
    ): Result<SecureLinksResponse> = withContext(Dispatchers.IO) {
        try {
            val credentials = GOGAuthManager.getStoredCredentials(context).getOrNull()
            if (credentials == null) {
                return@withContext Result.failure(Exception("Not authenticated"))
            }

            // Build secure link URL based on generation
            var url = if (generation == 2) {
                "$GOG_CONTENT_SYSTEM/products/$productId/secure_link?_version=2&generation=2&path=$path"
            } else {
                "$GOG_CONTENT_SYSTEM/products/$productId/secure_link?_version=2&type=depot&path=$path"
            }

            // Add root parameter if provided (for patches)
            if (root != null) {
                url += "&root=$root"
            }

            Timber.tag("GOG").d("Getting secure link for product $productId (gen $generation)")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to get secure link: HTTP ${response.code}"),
                )
            }

            val jsonStr = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            // Log the actual response to debug parsing issues
            Timber.tag("GOG").d("Secure link response: $jsonStr")

            val secureLinks = parser.parseSecureLinks(jsonStr)

            Timber.tag("GOG").d("Got ${secureLinks.urls.size} secure URL(s) for product $productId")

            Result.success(secureLinks)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to get secure link for product $productId")
            Result.failure(e)
        }
    }

    /**
     * Convert manifest hash to GOG Galaxy CDN path format
     *
     * Format: AA/BB/CCDD... where AA, BB are first two pairs of hex digits
     * Example: "abc123..." -> "ab/c1/abc123..."
     */
    private fun gogGalaxyPath(hash: String): String {
        if (hash.length < 4) return hash
        return "${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash"
    }
}
