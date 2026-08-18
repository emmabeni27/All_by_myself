import React, { useEffect } from 'react';
import App from './App.tsx'
import './index.css'
import {createRoot} from "react-dom/client";
import {PaginationProvider} from "./contexts/paginationContext.tsx";
import {SnackbarProvider} from "./contexts/snackbarContext.tsx";
import {Auth0Provider, useAuth0} from "@auth0/auth0-react";
import { ServiceProvider, useServices } from "./contexts/serviceContext.tsx";

function AuthWrapper({ children }: { children: React.ReactNode }) {
    const { isAuthenticated, user } = useAuth0();
    const { runnerService, apiService } = useServices();
    useEffect(() => {
        const registerUserOnAuth = async () => {
            if (isAuthenticated && user?.sub) {
                try {
                    await runnerService.registerUser(user.sub);
                    await apiService.registerUser();
                } catch (error) {
                    if (!(error instanceof Error) || !error.message.
                    includes("409")) {
                        console.error('Error registering user:', error);
                    }
                }
            }
        };
        registerUserOnAuth();
    }, [isAuthenticated, user, runnerService, apiService]);
    return <>{children}</>;
}

createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <Auth0Provider
            domain={import.meta.env.VITE_AUTH0_DOMAIN ?? ""}
            clientId={import.meta.env.VITE_AUTH0_CLIENT_ID ?? ""}
            authorizationParams={{
                redirect_uri: window.location.origin,
                audience: import.meta.env.VITE_AUTH0_AUDIENCE,
                scope: "openid profile email read:snippets write:snippets delete:snippets",
                connection: import.meta.env.VITE_AUTH0_REALM
            }}
            useRefreshTokens={true}
            cacheLocation="localstorage"
        >
            <ServiceProvider>
                <AuthWrapper>
                    <PaginationProvider>
                        <SnackbarProvider>
                            <App/>
                        </SnackbarProvider>
                    </PaginationProvider>
                </AuthWrapper>
            </ServiceProvider>
        </Auth0Provider>
    </React.StrictMode>,
)