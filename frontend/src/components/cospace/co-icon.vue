<template>
    <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
    >
        <path :d="paths[name] || paths.file" />
    </svg>
</template>
<script setup>
defineProps({ name: { type: String, default: 'file' } })
const paths = {
    grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
    folder: 'M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z',
    file: 'M14 3H5v18h14V8ZM14 3v5h5',
    users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    inbox: 'M4 3h16l2 12v6H2v-6ZM2 15h6l2 3h4l2-3h6',
    share: 'M14 4h6v6 M20 4l-9 9 M9 5H4v15h15v-5',
    trash: 'M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7',
    image: 'M3 3h18v18H3z M3 16l5-5 4 4 3-3 6 6 M15 7h.01',
    video: 'M3 5h13v14H3z M16 10l5-3v10l-5-3',
    music: 'M9 18V5l12-2v13 M9 8l12-2 M9 18a3 3 0 1 1-3-3 3 3 0 0 1 3 3 M21 16a3 3 0 1 1-3-3 3 3 0 0 1 3 3',
    search: 'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
    arrow: 'M5 12h14 M13 6l6 6-6 6',
    chevron: 'M9 5l7 7-7 7',
    menu: 'M4 6h16 M4 12h16 M4 18h16',
    close: 'M6 6l12 12 M18 6L6 18',
    cloud: 'M7 18a5 5 0 0 1-1-9.9 7 7 0 0 1 13-1.2A5 5 0 0 1 19 18 M12 21V12 M8 16l4-4 4 4',
    check: 'M5 12l4 4L19 6',
    down: 'M7 10l5 5 5-5',
    refresh: 'M19 8a8 8 0 1 0 1 7 M19 3v5h-5',
    upload: 'M12 16V3 M7 8l5-5 5 5 M4 15v6h16v-6',
    plus: 'M12 5v14 M5 12h14',
}
</script>
<style scoped>
svg {
    width: 20px;
    height: 20px;
    flex-shrink: 0;
    vertical-align: middle;
}
</style>
