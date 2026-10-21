import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AdsBanner } from './shared/components/ads-banner/ads-banner';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, AdsBanner],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {}
