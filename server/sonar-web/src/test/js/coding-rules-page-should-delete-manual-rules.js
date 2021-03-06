/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
/* global casper:false */

var lib = require('../lib');

lib.initMessages();
lib.changeWorkingDirectory('coding-rules-page-should-delete-manual-rules');
lib.configureCasper();


casper.test.begin('coding-rules-page-should-delete-manual-rules', 1, function (test) {
  casper
      .start(lib.buildUrl('coding-rules'), function () {
        lib.setDefaultViewport();


        lib.mockRequestFromFile('/api/rules/app', 'app.json');
        this.searchMock = lib.mockRequestFromFile('/api/rules/search', 'search-before.json');
        lib.mockRequestFromFile('/api/rules/show', 'show.json');
        lib.mockRequest('/api/rules/delete', '{}');
        lib.mockRequest('/api/issues/search', '{}');
      })

      .then(function () {
        casper.evaluate(function () {
          require(['apps/coding-rules/app']);
        });
      })

      .then(function () {
        casper.waitForSelector('.coding-rule.selected', function () {
          casper.click('.coding-rule.selected .js-rule');
        });
      })

      .then(function () {
        casper.waitForSelector('.js-delete');
      })

      .then(function () {
        casper.click('.js-delete');
        casper.waitForSelector('[data-confirm="yes"]');
      })

      .then(function () {
        lib.clearRequestMock(this.searchMock);
        lib.mockRequestFromFile('/api/rules/search', 'search-after.json');
        casper.click('[data-confirm="yes"]');
        casper.waitForSelectorTextChange('#coding-rules-total');
      })

      .then(function () {
        test.assertSelectorContains('#coding-rules-total', 0);
      })

      .then(function () {
        lib.sendCoverage();
      })

      .run(function () {
        test.done();
      });
});
