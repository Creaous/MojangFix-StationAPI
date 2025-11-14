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

package pl.telvarost.mojangfixstationapi.mixin.client.auth;

import com.github.steveice10.mc.auth.exception.request.InvalidCredentialsException;
import com.github.steveice10.mc.auth.exception.request.RequestException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.ClientNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.handshake.HandshakePacket;
import net.minecraft.network.packet.login.LoginHelloPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.telvarost.mojangfixstationapi.Config;
import pl.telvarost.mojangfixstationapi.MojangFixStationApiMod;
import pl.telvarost.mojangfixstationapi.mixinterface.SessionAccessor;

import java.net.URI;

@Mixin(ClientNetworkHandler.class)
public abstract class ClientNetworkHandlerMixin {
    @Shadow
    private Minecraft minecraft;

    @Shadow
    private Connection connection;

    @Shadow
    public abstract void sendPacket(Packet arg);

    @Redirect(method = "onHandshake", at = @At(value = "INVOKE", target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"))
    private boolean checkServerId(String serverId, Object offline) {
        return serverId.trim().isEmpty() || serverId.equals(offline) || this.minecraft.session.sessionId.trim().isEmpty() || this.minecraft.session.sessionId.equals(offline);
    }

    @Inject(method = "onHandshake", at = @At(value = "NEW", target = "java/net/URL"), cancellable = true)
    private void onJoinServer(HandshakePacket packet, CallbackInfo ci) {
        this.authenticate(packet, ci);
    }

    private boolean attemptAuthentication(HandshakePacket packet, SessionAccessor session) throws RequestException {
        if (session.getGameProfile() == null || session.getAccessToken() == null) {
            throw new RequestException("Invalid access token!");
        }

        SessionAccessor.SESSION_SERVICE.joinServer(session.getGameProfile(), session.getAccessToken(), packet.name);
        this.sendPacket(new LoginHelloPacket(this.minecraft.session.username, 14));
        return true; // Success!
    }

    private void authenticate(HandshakePacket packet, CallbackInfo ci) {
        SessionAccessor session = (SessionAccessor) this.minecraft.session;

        URI originalBaseUri = SessionAccessor.SESSION_SERVICE.getBaseUri();
        if (Config.config.USE_CUSTOM_AUTH) {
            SessionAccessor.SESSION_SERVICE.setBaseUri(Config.config.SESSION_URL);
        }

        try {
            if (attemptAuthentication(packet, session)) {
                ci.cancel();
                return; // Success
            }
        } catch (RequestException e) {
            if (!Config.config.FALLBACK_TO_MOJANG || !Config.config.USE_CUSTOM_AUTH) {
                this.connection.disconnect("disconnect.loginFailedInfo", e.getClass().getSimpleName() + "\n" + e.getMessage());
                return;
            }

            MojangFixStationApiMod.getLogger().warn("Custom authentication failed with InvalidCredentials. Falling back to Mojang.");

            SessionAccessor.SESSION_SERVICE.setBaseUri(originalBaseUri);

            try {
                if (attemptAuthentication(packet, session)) {
                    ci.cancel();
                    return; // Success on retry
                }
            } catch (RequestException finalE) {
                this.connection.disconnect("disconnect.loginFailedInfo", finalE.getClass().getSimpleName() + "\n" + finalE.getMessage());
            }

        }

        ci.cancel();
    }
}
