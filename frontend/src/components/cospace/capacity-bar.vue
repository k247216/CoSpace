<template>
    <div class="capacity">
        <div class="capacity-numbers">
            <span
                >{{ formatBytes(occupied) }} <small>/ {{ formatBytes(total) }}</small></span
            ><span>{{ percentageText }}</span>
        </div>
        <div
            class="capacity-track"
            role="progressbar"
            :aria-label="label"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="percentage"
            :aria-valuetext="`已用 ${formatBytes(used)}，预占 ${formatBytes(reserved)}，总容量 ${formatBytes(total)}`"
        >
            <span class="capacity-used" :style="segment(used)" /><span
                class="capacity-reserved"
                :style="segment(reserved)"
            />
        </div>
        <div v-if="reserved > 0" class="capacity-legend">
            <span><i />已用 {{ formatBytes(used) }}</span
            ><span><i class="reserved" />预占 {{ formatBytes(reserved) }}</span>
        </div>
    </div>
</template>
<script setup>
import { computed } from 'vue'
import { formatBytes } from '../../utils/format'
const props = defineProps({
    used: { type: Number, default: 0 },
    reserved: { type: Number, default: 0 },
    total: { type: Number, required: true },
    label: { type: String, default: '容量占用' },
})
const occupied = computed(() => props.used + props.reserved)
const percentage = computed(() =>
    props.total > 0 ? Math.min(100, (occupied.value / props.total) * 100) : 0,
)
const percentageText = computed(() =>
    percentage.value > 0 && percentage.value < 0.1
        ? '<0.1%'
        : percentage.value.toFixed(1).replace('.0', '') + '%',
)
function segment(bytes) {
    return {
        width: (props.total > 0 ? Math.max(0, Math.min(100, (bytes / props.total) * 100)) : 0) + '%',
        minWidth: bytes > 0 && props.total > 0 ? '3px' : '0',
    }
}
</script>
<style scoped>
.capacity {
    width: 100%;
    min-width: 0;
}
.capacity-numbers {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 10px;
    font-size: 11px;
    color: #596e4c;
    margin-bottom: 10px;
}
.capacity-numbers small {
    font-size: 10px;
    color: #8b987d;
}
.capacity-numbers > span:last-child {
    font-size: 10px;
    color: #829373;
    white-space: nowrap;
}
.capacity-track {
    height: 7px;
    border-radius: 5px;
    background: #d4ddc8;
    display: flex;
    overflow: hidden;
    box-shadow: inset 0 0 0 1px #cbd7bf66;
}
.capacity-used {
    display: block;
    flex-shrink: 0;
    background: #5f7c4e;
    border-radius: 5px;
    transition: width 0.25s;
}
.capacity-reserved {
    display: block;
    flex-shrink: 0;
    background: #c09a69;
    transition: width 0.25s;
}
.capacity-legend {
    display: flex;
    flex-wrap: wrap;
    gap: 7px 12px;
    font-size: 9px;
    color: #839174;
    margin-top: 9px;
}
.capacity-legend span {
    display: flex;
    align-items: center;
    gap: 4px;
}
.capacity-legend i {
    width: 5px;
    height: 5px;
    background: #5f7c4e;
    border-radius: 50%;
}
.capacity-legend i.reserved {
    background: #c09a69;
}
</style>
