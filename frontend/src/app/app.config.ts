import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // The store is the only fetcher in this app; it gets one fetch-based client.
    //
    // provideAnimations() is deliberately not here: the shell's Material parts
    // (toolbar, progress-bar, flat button) and the rail's native controls render
    // correctly without the animation engine — Material degrades to instant
    // transitions — and skipping it keeps the entry bundle smaller. Re-add it with
    // a bundle measurement if a later task needs a genuinely animated surface.
    provideHttpClient(withFetch()),
  ]
};
