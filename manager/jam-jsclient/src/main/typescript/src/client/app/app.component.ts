import { Component, Inject } from '@angular/core';
import { ROUTER_DIRECTIVES } from '@angular/router';
import { HTTP_PROVIDERS } from '@angular/http';

import { Config, SidebarComponent, ShopEventBus } from './shared/index';

import { TranslateService, TranslatePipe } from 'ng2-translate/ng2-translate';


/**
 * This class represents the main application component. Within the @Routes annotation is the configuration of the
 * applications routes, configuring the paths for the lazy loaded components (HomeComponent, AboutComponent).
 */
@Component({
  moduleId: module.id,
  selector: 'yc-app',
  viewProviders: [HTTP_PROVIDERS],
  templateUrl: 'app.component.html',
  directives: [ROUTER_DIRECTIVES, SidebarComponent],
  pipes: [TranslatePipe]
})
export class AppComponent {

  constructor(@Inject(ShopEventBus)  _shopEventBus:ShopEventBus,
              translate: TranslateService) {

    console.log('AppComponent environment config', Config);
    ShopEventBus.init(_shopEventBus);

    var userLang = navigator.language.split('-')[0]; // use navigator lang if available
    userLang = /(uk|ru|en|de)/gi.test(userLang) ? userLang : 'en';
    console.log('AppComponent language', userLang);
    translate.setDefaultLang('en');
    translate.use(userLang);
  }

}
