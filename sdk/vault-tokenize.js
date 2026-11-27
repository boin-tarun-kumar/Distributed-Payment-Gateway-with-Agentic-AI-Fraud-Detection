export async function tokenizeCard(
  cardDetails,
  { cardVaultUrl, fetchImpl = globalThis.fetch } = {},
) {
  if (typeof fetchImpl !== "function") {
    throw new Error("A Fetch API implementation is required");
  }
  if (typeof cardVaultUrl !== "string" || cardVaultUrl.length === 0) {
    throw new TypeError("cardVaultUrl must identify the card-vault origin");
  }
  const vaultOrigin = new URL(cardVaultUrl);
  if (vaultOrigin.protocol !== "https:" && vaultOrigin.hostname !== "localhost") {
    throw new TypeError("The card vault must be accessed over HTTPS");
  }

  const { cardNumber, cvv } = cardDetails ?? {};
  if (typeof cardNumber !== "string" || typeof cvv !== "string") {
    throw new TypeError("cardNumber and cvv must be strings");
  }

  const response = await fetchImpl(`${vaultOrigin.origin}/api/v1/tokens`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cardNumber, cvv }),
    mode: "cors",
    credentials: "omit",
    cache: "no-store",
    referrerPolicy: "no-referrer",
  });

  if (!response.ok) {
    throw new Error(`Card vault tokenization failed (${response.status})`);
  }

  const result = await response.json();
  if (typeof result.cardToken !== "string" || result.cardToken.length === 0) {
    throw new Error("Card vault returned an invalid token response");
  }

  return result.cardToken;
}
