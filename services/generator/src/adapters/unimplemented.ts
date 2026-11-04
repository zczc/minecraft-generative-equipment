import type {
  ModelProvider,
  CandidateValidator,
  PackageBuilder,
  DefinitionPublisher,
} from "../ports/index.js";
import { notImplemented } from "../errors.js";
export class AliyunModelProvider implements ModelProvider {
  async generate(): Promise<never> {
    throw notImplemented("Aliyun model adapter");
  }
}
export class ValidatorProcess implements CandidateValidator {
  async validate(): Promise<never> {
    throw notImplemented("Isolated QuickJS validator");
  }
}
export class CandidatePackageBuilder implements PackageBuilder {
  async build(): Promise<never> {
    throw notImplemented("Candidate package builder");
  }
}
export class DefinitionRepository implements DefinitionPublisher {
  async publish(): Promise<never> {
    throw notImplemented("Verified definition publisher");
  }
}
