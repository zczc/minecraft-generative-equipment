package dev.generative.equipment.core;

import static dev.generative.equipment.core.RuntimeTypes.*;

import com.fasterxml.jackson.databind.JsonNode;

/** 后台准备完成后发布的定义；业务状态只留在 ItemStack，不写入这里。 */
public record LoadedDefinition(
    PreparedDefinition program, JsonNode equipment, StateSchema stateSchema, JsonNode provenance) {}
