import { TestBed } from '@angular/core/testing';
import { NativeSession } from './native-session';

describe('NativeSession', () => {
  it('is inactive in the default website environment', () => {
    TestBed.configureTestingModule({});
    const session = TestBed.inject(NativeSession);
    expect(session.isNative()).toBeFalse();
    expect(session.accessToken()).toBeNull();
  });
});
