import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/** Redirect target for permissionGuard — reached when a logged-in user lacks the required PERMISSION_*. */
@Component({
  selector: 'app-forbidden',
  imports: [RouterLink, MatButtonModule, MatIconModule],
  templateUrl: './forbidden.html',
  styleUrl: '../error-page.scss',
})
export class Forbidden {}
