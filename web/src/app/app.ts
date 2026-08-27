import { Component, signal } from '@angular/core';
import { Movers } from './movers';
import { Search } from './search';
import { Sets } from './sets';

type Tab = 'search' | 'movers' | 'sets';

/**
 * Three screens, switched by a signal rather than the router. Nothing needs
 * a deep link yet, so wiring up @angular/router would be ceremony. Revisit
 * when a screen does need its own URL, for sharing a link or for browser
 * back/forward. That's where routing starts earning its keep, and with a
 * third screen it's closer than it was.
 */
@Component({
  selector: 'app-root',
  imports: [Search, Movers, Sets],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly tab = signal<Tab>('search');
}
