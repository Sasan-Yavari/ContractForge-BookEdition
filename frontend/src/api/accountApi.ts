import { DefaultService } from './generated';

export type AccountApi = {
  deleteAccount: () => Promise<void>;
};

export const accountApi: AccountApi = {
  deleteAccount: () => DefaultService.deleteAccount(),
};
