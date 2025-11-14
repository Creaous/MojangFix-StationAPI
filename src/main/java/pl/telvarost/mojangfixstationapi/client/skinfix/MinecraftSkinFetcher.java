/*
 * Copyright (C) 2024-2025 js6pak
 *
 * This file is part of MojangFixStationAPI.
 *
 * MojangFixStationAPI is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, version 3.
 *
 * MojangFixStationAPI is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with MojangFixStationAPI. If not, see <https://www.gnu.org/licenses/>.
 */

package pl.telvarost.mojangfixstationapi.client.skinfix;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonArray;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.api.SyntaxError;
import pl.telvarost.mojangfixstationapi.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

public class MinecraftSkinFetcher {

    // Primary URL (Custom Server)
    private static final String CUSTOM_SKIN_URL = Config.config.PROFILE_URL;

    // Fallback URL (Mojang Session Server)
    private static final String MOJANG_PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    /**
     * Attempts to get the skin URL first from the Custom Server, then falls back to Mojang.
     * @param uuid The player's UUID.
     * @return The skin URL, or an empty string if both fail.
     */
    public static String getSkinUrl(String uuid) {
        // 1. Attempt Custom Server (Primary)
        String skinUrl = getTextureUrl(uuid, CUSTOM_SKIN_URL, "SKIN");

        if (skinUrl.isEmpty() && Config.config.FALLBACK_TO_MOJANG) {
            // 2. Fallback to Mojang API
            System.out.println("[MojangFix] Custom server failed for " + uuid + ", falling back to Mojang Skin URL.");
            skinUrl = getTextureUrl(uuid, MOJANG_PROFILE_URL, "SKIN");
        }

        return skinUrl;
    }

    /**
     * Attempts to get the cape URL first from the Custom Server, then falls back to Mojang.
     * @param uuid The player's UUID.
     * @return The cape URL, or an empty string if both fail.
     */
    public static String getCapeUrl(String uuid) {
        // 1. Attempt Custom Server (Primary)
        String capeUrl = getTextureUrl(uuid, CUSTOM_SKIN_URL, "CAPE");

        if (capeUrl.isEmpty() && Config.config.FALLBACK_TO_MOJANG) {
            // 2. Fallback to Mojang API
            System.out.println("[MojangFix] Custom server failed for " + uuid + ", falling back to Mojang Cape URL.");
            capeUrl = getTextureUrl(uuid, MOJANG_PROFILE_URL, "CAPE");
        }

        return capeUrl;
    }

    /**
     * Checks for slim arms (Alex model) first from the Custom Server, then falls back to Mojang.
     * @param uuid The player's UUID.
     * @return true if the skin has slim arms, false otherwise.
     */
    public static boolean hasSlimArms(String uuid) {
        // 1. Attempt Custom Server (Primary)
        Boolean isSlim = checkSlimArms(uuid, CUSTOM_SKIN_URL);

        if (isSlim == null && Config.config.FALLBACK_TO_MOJANG) {
            // 2. Fallback to Mojang API
            System.out.println("[MojangFix] Custom server failed for " + uuid + ", falling back to Mojang model check.");
            isSlim = checkSlimArms(uuid, MOJANG_PROFILE_URL);
        }

        // If both failed, default to the classic model (false/Steve)
        return isSlim != null && isSlim;
    }

    /**
     * Generic method to retrieve a texture URL (SKIN or CAPE) from a specified base URL.
     */
    private static String getTextureUrl(String uuid, String baseUrl, String type) {
        try {
            String profileJson = fetchProfileJson(uuid, baseUrl);
            if (profileJson == null) {
                return ""; // Fetch failed
            }

            String base64Textures = extractBase64Textures(profileJson);
            if (base64Textures == null) {
                return ""; // No textures property found
            }

            String decodedTextures = new String(Base64.getDecoder().decode(base64Textures));
            JsonObject decodedJson = parseJson(decodedTextures);

            if (decodedJson != null && decodedJson.getObject("textures") != null) {
                JsonObject textureObject = decodedJson.getObject("textures").getObject(type);
                if (textureObject != null && textureObject.get(String.class, "url") != null) {
                    return textureObject.get(String.class, "url");
                }
            }
        } catch (Exception e) {
            // Print stack trace for primary server failures to aid debugging
            System.err.println("[MojangFix] Error fetching " + type + " from " + baseUrl + " for " + uuid + ": " + e.getMessage());
        }
        return "";
    }

    /**
     * Attempts to check for slim arms from a specified base URL. Returns null on failure.
     */
    private static Boolean checkSlimArms(String uuid, String baseUrl) {
        try {
            String profileJson = fetchProfileJson(uuid, baseUrl);
            if (profileJson == null) {
                return null; // Server fetch failed
            }

            String base64Textures = extractBase64Textures(profileJson);
            if (base64Textures == null) {
                return false; // No textures property means default skin, which is Classic/Steve (false)
            }

            String decodedTextures = new String(Base64.getDecoder().decode(base64Textures));
            JsonObject decodedJson = parseJson(decodedTextures);

            if (decodedJson != null && decodedJson.getObject("textures") != null) {
                JsonObject skinObject = decodedJson.getObject("textures").getObject("SKIN");
                if (skinObject != null && skinObject.get(String.class, "metadata") != null) {
                    JsonObject metadata = skinObject.getObject("metadata");
                    if (metadata != null && "slim".equals(metadata.get(String.class, "model"))) {
                        return true;
                    }
                }
                // If skin data is present but model is not slim, it's Classic/Steve
                return false;
            }
        } catch (Exception e) {
            System.err.println("[MojangFix] Error checking slim arms from " + baseUrl + " for " + uuid + ": " + e.getMessage());
        }
        return null; // Return null if a critical error or exception occurred
    }


    /**
     * Fetches the raw JSON profile from a specified URL. Returns null on failure (network error or non-200 response).
     */
    private static String fetchProfileJson(String uuid, String baseUrl) {
        try {
            URL url = new URL(baseUrl + uuid);
            // System.out.println("Fetching profile from: " + url.toString()); // For detailed debug

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000); // 5 second timeout
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();

            // Only proceed if the response is successful (200 OK)
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }

                in.close();
                connection.disconnect();
                return content.toString();
            } else {
                // Return null on non-200 responses (e.g., 404 Not Found, 429 Rate Limit, 500 Server Error)
                System.err.println("[MojangFix] Failed to fetch profile from " + baseUrl + " for " + uuid + ". Response code: " + responseCode);
                connection.disconnect();
                return null;
            }
        } catch (Exception e) {
            // Catches network errors, timeouts, etc.
            System.err.println("[MojangFix] Network error fetching profile from " + baseUrl + " for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the Base64-encoded 'textures' property value from the raw profile JSON.
     */
    private static String extractBase64Textures(String profileJson) {
        try {
            JsonObject profileObject = parseJson(profileJson);
            if (profileObject != null) {
                JsonElement propertiesElement = profileObject.get("properties");
                if (propertiesElement instanceof JsonArray) {
                    JsonArray properties = (JsonArray) propertiesElement;

                    for (JsonElement propertyElement : properties) {
                        if (propertyElement instanceof JsonObject) {
                            JsonObject property = (JsonObject) propertyElement;
                            if ("textures".equals(property.get(String.class, "name"))) {
                                return property.get(String.class, "value");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Parses a JSON string into a Jankson JsonObject.
     */
    private static JsonObject parseJson(String json) {
        try {
            Jankson jankson = Jankson.builder().build();
            return jankson.load(json);
        } catch (SyntaxError e) {
            e.printStackTrace();
        }
        return null;
    }
}