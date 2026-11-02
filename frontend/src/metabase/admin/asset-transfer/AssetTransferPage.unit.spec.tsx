import userEvent from "@testing-library/user-event";
import fetchMock from "fetch-mock";

import {
  setupCardsEndpoints,
  setupDatabaseListEndpoint,
} from "__support__/server-mocks";
import { createMockState } from "__support__/state";
import { renderWithProviders, screen, waitFor } from "__support__/ui";
import { createMockDatabase, createMockUser } from "metabase-types/api/mocks";

import { AssetTransferPage } from "./AssetTransferPage";
import { LAST_IMPORT_KEY } from "./utils";

jest.mock("metabase/common/components/Pickers/CollectionPicker", () => ({
  CollectionPickerModal: ({
    onChange,
  }: {
    onChange: (value: unknown) => void;
  }) => (
    <button
      onClick={() =>
        onChange({ id: 20, name: "Analytics", model: "collection" })
      }
    >
      Choose Analytics
    </button>
  ),
}));

const bundle = {
  format: "metabase-asset-transfer",
  format_version: 1,
  producer: { metabase_version: "test", transfer_version: 1 },
  databases: [{ id: 1, name: "Source", engine: "postgres", details: {} }],
  tables: [],
  fields: [],
  cards: [{ entity_id: "source-card", name: "Revenue" }],
};

function setup(lastRequest?: string) {
  sessionStorage.clear();
  if (lastRequest) {
    sessionStorage.setItem(LAST_IMPORT_KEY, lastRequest);
  }
  setupCardsEndpoints([]);
  setupDatabaseListEndpoint([
    createMockDatabase({ id: 10, name: "Target", engine: "postgres" }),
    createMockDatabase({ id: 11, name: "Another", engine: "postgres" }),
  ]);
  renderWithProviders(<AssetTransferPage />, {
    storeInitialState: createMockState({
      currentUser: createMockUser({ is_superuser: true }),
    }),
    withRouter: true,
  });
}

async function configureImport() {
  const file = new File([JSON.stringify(bundle)], "assets.json", {
    type: "application/json",
  });
  Object.defineProperty(file, "text", {
    value: async () => JSON.stringify(bundle),
  });
  await userEvent.upload(screen.getByLabelText("Upload transfer file"), file);
  await userEvent.click(
    await screen.findByLabelText("Target connection for Source"),
  );
  await userEvent.click(
    await screen.findByRole("option", { name: "Target (#10)" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Choose destination collection" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Choose Analytics" }),
  );
}

describe("AssetTransferPage", () => {
  it("blocks execution on conflicts and discards a preview when the mapping changes", async () => {
    fetchMock.post("path:/api/asset-transfer/import/preview", {
      can_import: false,
      plan_hash: "hash",
      items: [
        {
          name: "Revenue",
          source_entity_id: "source-card",
          action: "conflict",
          error: { code: "ASSET_CHANGED", message: "Target was changed" },
        },
      ],
    });
    setup();
    await configureImport();
    await userEvent.click(
      screen.getByRole("button", { name: "Run preflight" }),
    );
    expect(await screen.findByText("Target was changed")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Import definitions" }),
    ).toBeDisabled();
    await userEvent.click(
      screen.getByLabelText("Target connection for Source"),
    );
    await userEvent.click(
      await screen.findByRole("option", { name: "Another (#11)" }),
    );
    expect(
      screen.queryByRole("button", { name: "Import definitions" }),
    ).not.toBeInTheDocument();
  });

  it("reuses the request ID after a failed request and exposes the imported question", async () => {
    fetchMock.post("path:/api/asset-transfer/import/preview", {
      can_import: true,
      plan_hash: "hash",
      items: [
        { name: "Revenue", source_entity_id: "source-card", action: "create" },
      ],
    });
    const requests: { request_id: string }[] = [];
    fetchMock.post("path:/api/asset-transfer/import", async (call) => {
      const body = await call.request?.json();
      requests.push(body);
      return requests.length === 1
        ? { status: 500, body: { message: "Try again" } }
        : {
            status: 200,
            body: {
              request_id: body.request_id,
              status: "complete",
              items: [
                {
                  name: "Revenue",
                  source_entity_id: "source-card",
                  action: "created",
                  card_id: 50,
                },
              ],
            },
          };
    });
    setup();
    await configureImport();
    await userEvent.click(
      screen.getByRole("button", { name: "Run preflight" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Import definitions" }),
    );
    expect(await screen.findByText("Try again")).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: "Retry this import" }),
    );
    expect(
      await screen.findByRole("link", { name: "Open question" }),
    ).toHaveAttribute("href", "/question/50");
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[0].request_id).toEqual(requests[1].request_id);
    expect(sessionStorage.getItem(LAST_IMPORT_KEY)).toEqual(
      requests[0].request_id,
    );
  });

  it("recovers and retries pending events after a reload without uploading a file", async () => {
    const requestId = "12345678-1234-1234-1234-123456789012";
    const result = {
      request_id: requestId,
      status: "events_pending",
      items: [
        {
          source_entity_id: "source-card",
          name: "Revenue",
          action: "created",
          card_id: 50,
        },
      ],
    };
    fetchMock.get(`path:/api/asset-transfer/imports/${requestId}`, result);
    fetchMock.post(
      `path:/api/asset-transfer/imports/${requestId}/retry-events`,
      { ...result, status: "complete" },
    );
    setup(requestId);
    await userEvent.click(
      screen.getByRole("button", { name: "Recover result" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Retry event delivery" }),
    );
    expect(
      await screen.findByText(
        "Definitions imported. Open the questions to verify their results.",
      ),
    ).toBeInTheDocument();
    expect(
      fetchMock.callHistory.calls("path:/api/asset-transfer/import"),
    ).toHaveLength(0);
  });
});
