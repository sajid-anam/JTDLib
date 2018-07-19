/*
 * Copyright (c) 2018. Ernesto Castellotti <erny.castell@gmail.com>
 * This file is part of JTdlib.
 *
 *     JTdlib is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     JTdlib is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with JTdlib.  If not, see <http://www.gnu.org/licenses/>.
 */

package it.ernytech.tdbot;

import it.ernytech.tdlib.*;
import it.ernytech.tdlib.utils.CloseCallback;
import it.ernytech.tdlib.utils.ErrorCallback;
import it.ernytech.tdlib.utils.ReceiveCallback;

import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Interface for easy interaction with TDLib for bot, used to implement other clients such as BotClient and UserClient.
 */
public class EasyClient {
    private AuthorizationHandler authorizationHandler;
    private ClientActor clientActor;
    private ConcurrentHashMap<Long, TdCallback> handlers = new ConcurrentHashMap<>();
    private AtomicLong requestId = new AtomicLong(1);
    protected volatile boolean haveAuthorization = false;

    /**
     * Creates a new EasyClient.
     * @param authorizationHandler Callback to be implemented in the client to manage the authorization.
     */
    public EasyClient(AuthorizationHandler authorizationHandler) {
        Log.setVerbosityLevel(1);
        this.authorizationHandler = authorizationHandler;
        this.handlers.put(0L, new TdCallback(response -> {}, error -> {}, () -> {}));
        this.clientActor = new ClientActor(new TdCallback(this::onResult, this::onError, this::onClosed));
    }

    /**
     * Send a request to tdlib without waiting for the result.
     * @param function TDLib API function representing a request to TDLib.
     * @return Request identifier. Responses to TDLib requests will have the same id as the corresponding request.
     */
    public long send(TdApi.Function function) {
        var requestId = this.requestId.getAndIncrement();
        this.clientActor.request(new Request(requestId, function));
        return requestId;
    }

    /**
     * Send a request to tdlib and send the response to the callbacks.
     * @param function TDLib API function representing a request to TDLib.
     * @param receiveCallback Interface of callback for receive incoming update or request response.
     * @param errorCallback Interface of callback for receive incoming error response.
     */
    public void execute(TdApi.Function function, ReceiveCallback receiveCallback, ErrorCallback errorCallback) {
        while (true) {
            if (haveAuthorization) {
                break;
            }
        }

        var requestId = send(function);
        this.handlers.put(requestId, new TdCallback(receiveCallback, errorCallback));
    }

    /**
     * Send a request to tdlib, wait for the result and return the response.
     * @param function TDLib API function representing a request to TDLib.
     * @return A response to a request.
     */
    public Response execute(TdApi.Function function) {
        var responseAtomicReference = new AtomicReference<Response>();
        var executedAtomicBoolean = new AtomicBoolean(false);

        execute(function, response -> {
            responseAtomicReference.set(response);
            executedAtomicBoolean.set(true);
        }, error -> {
            responseAtomicReference.set(error);
            executedAtomicBoolean.set(true);
        });

        while (true) {
            if (executedAtomicBoolean.get()) {
                return responseAtomicReference.get();
            }
        }
    }

    /**
     * Set the default callback for general updates (no response to requests).
     * @param receiveCallback Interface of callback for receive incoming update or request response.
     */
    public void setUpdateCallback(ReceiveCallback receiveCallback) {
        ErrorCallback errorCallback = null;
        CloseCallback closeCallback = null;

        if (this.handlers.containsKey(0L)) {
            var tdCallback = this.handlers.get(0L);

            if (tdCallback.getErrorCallback() != null) {
                errorCallback = tdCallback.getErrorCallback();
            }

            if (tdCallback.getCloseCallback() != null) {
                closeCallback = tdCallback.getCloseCallback();
            }
        }

        this.handlers.put(0L, new TdCallback(receiveCallback, errorCallback, closeCallback));
    }

    /**
     * Set the default callback for general error updates (no response to requests).
     * @param errorCallback Interface of callback for receive incoming error response.
     */
    public void setErrorCallback(ErrorCallback errorCallback) {
        ReceiveCallback receiveCallback = null;
        CloseCallback closeCallback = null;

        if (this.handlers.containsKey(0L)) {
            var tdCallback = this.handlers.get(0L);

            if (tdCallback.getReceiveCallback() != null) {
                receiveCallback = tdCallback.getReceiveCallback();
            }

            if (tdCallback.getCloseCallback() != null) {
                closeCallback = tdCallback.getCloseCallback();
            }
        }

        this.handlers.put(0L, new TdCallback(receiveCallback, errorCallback, closeCallback));
    }

    /**
     * Set the default callback for Tdlib closing notification.
     * @param closeCallback Interface of callback for receive notification of closing Tdlib.
     */
    public void setCloseCallback(CloseCallback closeCallback) {
        ReceiveCallback receiveCallback = null;
        ErrorCallback errorCallback = null;

        if (this.handlers.containsKey(0L)) {
            var tdCallback = this.handlers.get(0L);

            if (tdCallback.getReceiveCallback() != null) {
                receiveCallback = tdCallback.getReceiveCallback();
            }

            if (tdCallback.getErrorCallback() != null) {
                errorCallback = tdCallback.getErrorCallback();
            }
        }

        this.handlers.put(0L, new TdCallback(receiveCallback, errorCallback, closeCallback));
    }

    /**
     * Destroys the client and TDLib instance.
     */
    public void destroyBotClient() {
        this.clientActor.destroyClientActor();
        Log.setVerbosityLevel(5);
    }

    private void onResult(Response response) {
        if (response.getObject().getConstructor() == TdApi.UpdateAuthorizationState.CONSTRUCTOR) {
            authorizationHandler((TdApi.UpdateAuthorizationState) response.getObject());
            return;
        }

        if (response.getId() == 0) {
            this.handlers.get(0L).getReceiveCallback().onResult(response);
        } else {
            var tdCallback = this.handlers.remove(response.getId());

            if (tdCallback == null) {
                return;
            }

            var receiveCallback = tdCallback.getReceiveCallback();
            receiveCallback.onResult(response);
        }
    }

    private void onError(Response error) {
        if (error.getId() == 0) {
            this.handlers.get(0L).getErrorCallback().onError(error);
        } else {
            var tdCallback = this.handlers.remove(error.getId());

            if (tdCallback == null) {
                return;
            }

            tdCallback.getErrorCallback().onError(error);
        }
    }

    private void onClosed() {
        this.handlers.get(0L).getCloseCallback().onClosed();
    }

    /**
     * Manages the authorization state updates.
     * @param authorizationState The status of the authorization.
     */
    protected void authorizationHandler(TdApi.UpdateAuthorizationState authorizationState) {
        switch (authorizationState.authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR : {
                var parameters = new TdApi.TdlibParameters();
                parameters.databaseDirectory = "tdlib";
                parameters.useMessageDatabase = false;
                parameters.useSecretChats = false;
                parameters.apiId = 94575;
                parameters.apiHash = "a3406de8d171bb422bb6ddf3bbd800e2";
                parameters.systemLanguageCode = "en";
                parameters.deviceModel = "TDBOT";
                parameters.systemVersion = "TDBOT";
                parameters.applicationVersion = "1.0";
                parameters.enableStorageOptimizer = true;
                send(new TdApi.SetTdlibParameters(parameters));
                break;
            }

            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR: {
                send(new TdApi.CheckDatabaseEncryptionKey());
                break;
            }

            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                this.authorizationHandler.onAuthorizationStateWaitPhoneNumber(this);
                break;
            }

            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR: {
                var scanner = new Scanner(System.in);
                System.out.print("Insert your code: ");
                send(new TdApi.CheckAuthenticationCode(scanner.nextLine(), "", ""));
                System.out.println();
                break;
            }

            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR: {
                var scanner = new Scanner(System.in);
                System.out.print("Insert your password: ");
                send(new TdApi.CheckAuthenticationPassword(scanner.nextLine()));
                System.out.println();
                break;
            }

            case TdApi.AuthorizationStateReady.CONSTRUCTOR: {
                this.haveAuthorization = true;
                break;
            }
        }
    }
}
