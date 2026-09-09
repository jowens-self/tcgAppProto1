import { Component, signal } from '@angular/core';
import { Collection } from './collection';
import { Movers } from './movers';
import { Search } from './search';
import { Sets } from './sets';

type Tab = 'search' | 'movers' | 'sets' | 'collection';

/**
 * Four screens, switched by a signal rather than the router. Nothing needs a
 * deep link yet, so wiring up @angular/router would be ceremony. Revisit when
 * a screen does need its own URL, for sharing a link or for browser
 * back/forward. Collection makes that closer than it was: it is the first
 * screen someone would want to return to directly, and the first whose state
 * (an as-of date) is worth putting in a URL.
 */
@Component({
  selector: 'app-root',
  imports: [Search, Movers, Sets, Collection],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly tab = signal<Tab>('search');
}
