/*
 * Copyright (C) 2026 yuarmy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <errno.h>

#include "libavutil/error.h"
#include "graph/graphprint.h"

/*
 * 滤镜图打印只服务于 CLI 的 -print_graphs 调试选项，App 用不到。
 * 这里给出桩实现，避免把 graphprint 及其 HTML/CSS 资源一并链进来。
 */
int print_filtergraphs(FilterGraph **graphs, int nb_graphs,
                       InputFile **ifiles, int nb_ifiles,
                       OutputFile **ofiles, int nb_ofiles)
{
    (void)graphs;
    (void)nb_graphs;
    (void)ifiles;
    (void)nb_ifiles;
    (void)ofiles;
    (void)nb_ofiles;
    return AVERROR(ENOSYS);
}

int print_filtergraph(FilterGraph *fg, AVFilterGraph *graph)
{
    (void)fg;
    (void)graph;
    return AVERROR(ENOSYS);
}
