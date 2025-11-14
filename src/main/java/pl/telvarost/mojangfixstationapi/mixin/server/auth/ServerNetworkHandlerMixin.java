/*
 * Copyright (C) 2022-2025 js6pak
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

package pl.telvarost.mojangfixstationapi.mixin.server.auth;

import com.github.steveice10.mc.auth.data.GameProfile;
import com.github.steveice10.mc.auth.exception.request.InvalidCredentialsException;
import com.github.steveice10.mc.auth.exception.request.RequestException;
import com.github.steveice10.mc.auth.service.SessionService;
import net.minecraft.network.packet.login.LoginHelloPacket;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.*;
import pl.telvarost.mojangfixstationapi.Config;
import pl.telvarost.mojangfixstationapi.MojangFixStationApiMod;

@Mixin(targets = "net.minecraft.server.network.ServerLoginNetworkHandler$AuthThread")
public class ServerNetworkHandlerMixin {
    @Shadow
    @Final
    ServerLoginNetworkHandler networkHandler;

    @Shadow
    @Final
    LoginHelloPacket loginPacket;

    @Unique
    private static final SessionService SESSION_SERVICE = new SessionService();

    /**
     * @reason Swap auth logic completely
     * @author js6pak
     */
    @Overwrite(remap = false)
    public void run() {
        this.authenticate();
    }

    /**
     * Attempts to authenticate using the currently set SESSION_SERVICE URI.
     * * @return true if authentication succeeded.
     * @throws RequestException if the server responds with an error other than InvalidCredentialsException.
     * @throws InvalidCredentialsException if authentication fails due to invalid token/session.
     */
    private boolean attemptAuthentication(ServerNetworkHandlerAccessor accessor) throws RequestException {
        GameProfile gameProfile = SESSION_SERVICE.getProfileByServer(loginPacket.username, accessor.getServerId());

        if (gameProfile != null) {
            MojangFixStationApiMod.getLogger().info("Authenticated " + gameProfile.getName() + " as " + gameProfile.getId());
            accessor.setLoginPacket(loginPacket);
            return true; // Success
        }

        return false;
    }

    /**
     * Manages the two-step authentication sequence (Custom URL, then Mojang URL).
     */
    private void authenticate() {
        ServerNetworkHandlerAccessor accessor = (ServerNetworkHandlerAccessor) networkHandler;

        try {
            if (Config.config.USE_CUSTOM_AUTH) {
                SESSION_SERVICE.setBaseUri(Config.config.SESSION_URL);
            }

            if (attemptAuthentication(accessor)) {
                return; // Success, stop here
            }

        } catch (RequestException e) {
            if (!Config.config.FALLBACK_TO_MOJANG || !Config.config.USE_CUSTOM_AUTH) {
                MojangFixStationApiMod.getLogger().error("Custom authentication failed with general RequestException.", e);
                networkHandler.disconnect("Failed to verify username! [Connection Error]");
                return;
            }

            MojangFixStationApiMod.getLogger().warn("Custom authentication failed with InvalidCredentials. Falling back to Mojang.");

            try {
                SESSION_SERVICE.setBaseUri("https://sessionserver.mojang.com/");

                if (attemptAuthentication(accessor)) {
                    return; // Success on retry, stop here
                }
            } catch (RequestException finalE) {
                MojangFixStationApiMod.getLogger().error("Mojang authentication failed.", finalE);
                networkHandler.disconnect("Failed to verify username! [Mojang Error: " + finalE.getClass().getSimpleName() + "]");
                return;
            }

        } catch (Exception e) {
            networkHandler.disconnect("Failed to verify username! [internal error " + e + "]");
            e.printStackTrace();
            return;
        }

        networkHandler.disconnect("Failed to verify username!");
    }
}