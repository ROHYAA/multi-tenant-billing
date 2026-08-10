import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/** Shown for 5xx responses / unreachable-backend (network) failures — see ApiError.httpStatus. */
@Component({
  selector: 'app-server-error',
  imports: [RouterLink, MatButtonModule, MatIconModule],
  templateUrl: './server-error.html',
  styleUrl: '../error-page.scss',
})
export class ServerError {}
