<template>
    <router-link class="co-brand" to="/"
        ><svg viewBox="0 0 32 32" aria-hidden="true">
            <rect width="32" height="32" rx="9" fill="currentColor" />
            <path
                d="M22 10a8.5 8.5 0 1 0 0 12"
                fill="none"
                stroke="#fffdf7"
                stroke-width="2.2"
                stroke-linecap="round"
            /></svg
        ><span>CoSpace</span></router-link
    >
</template>
<style scoped>
.co-brand {
    display: inline-flex;
    align-items: center;
    gap: 11px;
    color: var(--co-accent);
    text-decoration: none;
    font-weight: 650;
    font-size: 23px;
    letter-spacing: -1px;
    white-space: nowrap;
}
.co-brand svg {
    width: 31px;
    height: 31px;
    flex-shrink: 0;
}
</style>
