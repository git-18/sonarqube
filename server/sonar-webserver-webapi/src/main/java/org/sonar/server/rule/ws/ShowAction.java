/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.rule.ws;

import com.google.common.io.Resources;
import java.util.Collections;
import java.util.List;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.rule.RuleParamDto;
import org.sonar.server.exceptions.NotFoundException;
import org.sonarqube.ws.Rules.ShowResponse;

import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static org.sonar.server.ws.WsUtils.writeProtobuf;

/**
 * @since 4.4
 */
public class ShowAction implements RulesWsAction {

  public static final String PARAM_KEY = "key";
  public static final String PARAM_ACTIVES = "actives";

  private final DbClient dbClient;
  private final RuleMapper mapper;
  private final ActiveRuleCompleter activeRuleCompleter;
  private final RuleWsSupport ruleWsSupport;

  public ShowAction(DbClient dbClient, RuleMapper mapper, ActiveRuleCompleter activeRuleCompleter, RuleWsSupport ruleWsSupport) {
    this.dbClient = dbClient;
    this.activeRuleCompleter = activeRuleCompleter;
    this.mapper = mapper;
    this.ruleWsSupport = ruleWsSupport;
  }

  @Override
  public void define(WebService.NewController controller) {
    WebService.NewAction action = controller
      .createAction("show")
      .setDescription("Get detailed information about a rule<br>" +
        "Since 5.5, following fields in the response have been deprecated :" +
        "<ul>" +
        "<li>\"effortToFixDescription\" becomes \"gapDescription\"</li>" +
        "<li>\"debtRemFnCoeff\" becomes \"remFnGapMultiplier\"</li>" +
        "<li>\"defaultDebtRemFnCoeff\" becomes \"defaultRemFnGapMultiplier\"</li>" +
        "<li>\"debtRemFnOffset\" becomes \"remFnBaseEffort\"</li>" +
        "<li>\"defaultDebtRemFnOffset\" becomes \"defaultRemFnBaseEffort\"</li>" +
        "<li>\"debtOverloaded\" becomes \"remFnOverloaded\"</li>" +
        "</ul>" +
        "In 7.1, the field 'scope' has been added.<br/>" +
        "Since 9.5 :" +
        "<ul>" +
        "<li>the field \"htmlDesc\" has been deprecated.</li>" +
        "<li>the field \"descriptionSections\" has been added.</li>" +
        "</ul>")
      .setSince("4.2")
      .setResponseExample(Resources.getResource(getClass(), "show-example.json"))
      .setHandler(this);

    action
      .createParam(PARAM_KEY)
      .setDescription("Rule key")
      .setRequired(true)
      .setExampleValue("javascript:EmptyBlock");

    action
      .createParam(PARAM_ACTIVES)
      .setDescription("Show rule's activations for all profiles (\"active rules\")")
      .setBooleanPossibleValues()
      .setDefaultValue(false);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    RuleKey key = RuleKey.parse(request.mandatoryParam(PARAM_KEY));
    try (DbSession dbSession = dbClient.openSession(false)) {
      RuleDto rule = dbClient.ruleDao().selectByKey(dbSession, key)
        .orElseThrow(() -> new NotFoundException(format("Rule not found: %s", key)));

      List<RuleDto> templateRules = ofNullable(rule.getTemplateUuid())
        .flatMap(templateUuid -> dbClient.ruleDao().selectByUuid(rule.getTemplateUuid(), dbSession))
        .map(Collections::singletonList).orElseGet(Collections::emptyList);

      List<RuleParamDto> ruleParameters = dbClient.ruleDao().selectRuleParamsByRuleUuids(dbSession, singletonList(rule.getUuid()));
      ShowResponse showResponse = buildResponse(dbSession, request,
        new SearchAction.SearchResult()
          .setRules(singletonList(rule))
          .setTemplateRules(templateRules)
          .setRuleParameters(ruleParameters)
          .setTotal(1L));
      writeProtobuf(showResponse, request, response);
    }
  }

  private ShowResponse buildResponse(DbSession dbSession, Request request, SearchAction.SearchResult searchResult) {
    ShowResponse.Builder responseBuilder = ShowResponse.newBuilder();
    RuleDto rule = searchResult.getRules().get(0);
    responseBuilder.setRule(mapper.toWsRule(rule, searchResult, Collections.emptySet(),
      ruleWsSupport.getUsersByUuid(dbSession, searchResult.getRules()), emptyMap()));
    if (request.mandatoryParamAsBoolean(PARAM_ACTIVES)) {
      activeRuleCompleter.completeShow(dbSession, rule).forEach(responseBuilder::addActives);
    }
    return responseBuilder.build();
  }

}
