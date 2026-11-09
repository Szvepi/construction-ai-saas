export function getSenderInitial(fromAddress: string | null | undefined): string {
    const value = fromAddress?.trim() ?? "";
    if (!value) return "?";

    const displayName = value.match(/^\s*(.*?)\s*</)?.[1];
    const emailAddress = value.match(/<\s*([^<>]+?)\s*>/)?.[1] ?? value;
    const clean = (text: string | undefined) =>
        text?.replace(/^[\s"'“”‘’]+|[\s"'“”‘’]+$/g, "").trim() ?? "";
    const candidate = clean(displayName)
        || clean(emailAddress.split("@", 1)[0])
        || clean(value);
    const initial = Array.from(candidate)[0];

    return initial?.toLocaleUpperCase("hu-HU") ?? "?";
}
