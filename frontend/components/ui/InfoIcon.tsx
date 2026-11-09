type InfoIconProps = {
    className: string;
};

export function InfoIcon({className}: InfoIconProps) {
    return (
        <span
            aria-hidden="true"
            className={`inline-flex shrink-0 items-center justify-center rounded-full border border-current font-semibold leading-none ${className}`}
        >
            i
        </span>
    );
}
