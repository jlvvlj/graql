/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package graql.lang.query;

import graql.lang.common.GraqlToken;
import graql.lang.common.exception.ErrorMessage;
import graql.lang.common.exception.GraqlException;
import graql.lang.pattern.Conjunction;
import graql.lang.pattern.Pattern;
import graql.lang.pattern.variable.BoundVariable;
import graql.lang.pattern.variable.ThingVariable;
import graql.lang.pattern.variable.UnboundVariable;
import graql.lang.pattern.variable.Variable;
import graql.lang.query.builder.Aggregatable;
import graql.lang.query.builder.Sortable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static grakn.common.collection.Collections.list;
import static graql.lang.common.GraqlToken.Char.COMMA_SPACE;
import static graql.lang.common.GraqlToken.Char.NEW_LINE;
import static graql.lang.common.GraqlToken.Char.SEMICOLON;
import static graql.lang.common.GraqlToken.Char.SPACE;
import static graql.lang.common.GraqlToken.Command.GET;
import static graql.lang.common.GraqlToken.Command.GROUP;
import static graql.lang.common.GraqlToken.Filter.LIMIT;
import static graql.lang.common.GraqlToken.Filter.OFFSET;
import static graql.lang.common.GraqlToken.Filter.SORT;
import static graql.lang.common.exception.ErrorMessage.MISSING_PATTERNS;
import static graql.lang.common.exception.ErrorMessage.VARIABLE_NOT_NAMED;
import static graql.lang.common.exception.ErrorMessage.VARIABLE_OUT_OF_SCOPE;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;

public class GraqlMatch extends GraqlQuery implements Aggregatable<GraqlMatch.Aggregate> {

    private final Conjunction<? extends Pattern> conjunction;
    private final List<UnboundVariable> filter;
    private final Sortable.Sorting sorting;
    private final Long offset;
    private final Long limit;
    private final int hash;

    private List<BoundVariable> variables;
    private List<UnboundVariable> variablesNamedUnbound;

    GraqlMatch(final Conjunction<? extends Pattern> conjunction) {
        this(conjunction, new ArrayList<>());
    }

    GraqlMatch(final Conjunction<? extends Pattern> conjunction, final List<UnboundVariable> filter) {
        this(conjunction, filter, null, null, null);
    }

    // We keep this contructor 'public' as it is more efficient for query parsing
    public GraqlMatch(final Conjunction<? extends Pattern> conjunction, final List<UnboundVariable> filter, final Sortable.Sorting sorting, final Long offset, final Long limit) {
        if (filter == null) throw GraqlException.of(ErrorMessage.MISSING_FILTER_VARIABLES.message());
        this.conjunction = conjunction;
        this.filter = list(filter);
        this.sorting = sorting;
        this.offset = offset;
        this.limit = limit;

        for (UnboundVariable var : filter) {
            if (!variablesNamedUnbound().contains(var)) throw GraqlException.of(VARIABLE_OUT_OF_SCOPE.message(var));
            if (!var.isNamed()) throw GraqlException.of(VARIABLE_NOT_NAMED.message(var));
        }
        final List<UnboundVariable> sortableVars = filter.isEmpty() ? variablesNamedUnbound() : filter;
        if (sorting != null && !sortableVars.contains(sorting.var())) {
            throw GraqlException.of(VARIABLE_OUT_OF_SCOPE.message(sorting.var()));
        }

        // It is important that we use vars() (the method) and not vars (the constraint)
        // For reasons explained in the equals() method above
        this.hash = Objects.hash(this.conjunction, this.filter, this.sorting, this.offset, this.limit);
    }

    @Override
    public Aggregate aggregate(final GraqlToken.Aggregate.Method method, final UnboundVariable var) {
        return new Aggregate(this, method, var);
    }

    public Group group(final String var) {
        return group(UnboundVariable.named(var));
    }

    public Group group(final UnboundVariable var) {
        return new Group(this, var);
    }

    public Conjunction<? extends Pattern> conjunction() {
        return conjunction;
    }

    public List<BoundVariable> variables() {
        if (variables == null) variables = conjunction.variables().collect(toList());
        return variables;
    }

    List<UnboundVariable> variablesNamedUnbound() {
        if (variablesNamedUnbound == null) {
            variablesNamedUnbound = conjunction.variables().filter(Variable::isNamed)
                    .map(v -> UnboundVariable.named(v.name()))
                    .distinct().collect(toList());
        }
        return variablesNamedUnbound;
    }

    public List<UnboundVariable> filter() {
        if (filter.isEmpty()) return variablesNamedUnbound();
        else return filter;
    }

    public Optional<Sortable.Sorting> sort() {
        return Optional.ofNullable(sorting);
    }

    public Optional<Long> offset() {
        return Optional.ofNullable(offset);
    }

    public Optional<Long> limit() {
        return Optional.ofNullable(limit);
    }

    @Override
    public String toString() {
        final StringBuilder query = new StringBuilder();
        query.append(GraqlToken.Command.MATCH);

        if (conjunction.patterns().size() > 1) query.append(NEW_LINE);
        else query.append(SPACE);
        query.append(conjunction.patterns().stream().map(Object::toString).collect(joining("" + SEMICOLON + NEW_LINE)));
        query.append(SEMICOLON);

        if (!filter.isEmpty() || sort().isPresent() || sort().isPresent() || offset().isPresent() || limit().isPresent()) {
            if (conjunction.patterns().size() > 1) query.append(NEW_LINE);
            else query.append(SPACE);

            if (!filter.isEmpty()) { // Which is not equal to !vars().isEmpty()
                query.append(GET);
                final String varsStr = filter.stream().map(UnboundVariable::toString).collect(joining(COMMA_SPACE.toString()));
                query.append(SPACE).append(varsStr);
                query.append(SEMICOLON);
            }
            if (sort().isPresent()) query.append(SORT).append(SPACE).append(sorting).append(SEMICOLON).append(SPACE);
            if (offset().isPresent()) query.append(OFFSET).append(SPACE).append(offset).append(SEMICOLON).append(SPACE);
            if (limit().isPresent()) query.append(LIMIT).append(SPACE).append(limit).append(SEMICOLON).append(SPACE);
        }

        return query.toString().trim();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if (!getClass().isAssignableFrom(o.getClass()) && !o.getClass().isAssignableFrom(getClass())) {
            return false;
        }

        final GraqlMatch that = (GraqlMatch) o;

        // It is important that we use vars() (the method) and not vars (the constraint)
        // vars (the constraint) stores the variables as the user defined
        // vars() (the method) returns match.vars() if vars (the constraint) is empty
        // we want to compare vars() (the method) which determines the final value
        return (Objects.equals(this.conjunction, that.conjunction) &&
                Objects.equals(this.filter, that.filter) &&
                Objects.equals(this.sorting, that.sorting) &&
                Objects.equals(this.offset, that.offset) &&
                Objects.equals(this.limit, that.limit));
    }

    @Override
    public int hashCode() {
        return hash;
    }

    public static class Unfiltered extends GraqlMatch implements Sortable<Sorted, Offsetted, Limited> {

        public Unfiltered(final List<? extends Pattern> patterns) {
            super(validConjunction(patterns));
        }

        static Conjunction<? extends Pattern> validConjunction(final List<? extends Pattern> patterns) {
            if (patterns.size() == 0) throw GraqlException.of(MISSING_PATTERNS.message());
            return new Conjunction<>(patterns);
        }

        public GraqlMatch.Filtered get(final String var, final String... vars) {
            return get(concat(of(var), of(vars)).map(UnboundVariable::named).collect(toList()));
        }

        public GraqlMatch.Filtered get(final UnboundVariable var, final UnboundVariable... vars) {
            final List<UnboundVariable> varList = new ArrayList<>();
            varList.add(var);
            varList.addAll(Arrays.asList(vars));
            return get(varList);
        }

        public GraqlMatch.Filtered get(final List<UnboundVariable> vars) {
            return new GraqlMatch.Filtered(this, vars);
        }

        public GraqlMatch.Sorted sort(final Sorting sorting) {
            return new GraqlMatch.Sorted(this, sorting);
        }

        public GraqlMatch.Offsetted offset(final long offset) {
            return new GraqlMatch.Offsetted(this, offset);
        }

        public GraqlMatch.Limited limit(final long limit) {
            return new GraqlMatch.Limited(this, limit);
        }

        public final GraqlInsert insert(final ThingVariable<?>... things) {
            return new GraqlInsert(this, list(things));
        }

        public final GraqlInsert insert(final List<ThingVariable<?>> things) {
            return new GraqlInsert(this, things);
        }

        public final GraqlDelete delete(final ThingVariable<?>... things) {
            return new GraqlDelete(this, list(things));
        }

        public final GraqlDelete delete(final List<ThingVariable<?>> things) {
            return new GraqlDelete(this, things);
        }
    }

    public static class Filtered extends GraqlMatch implements Sortable<Sorted, Offsetted, Limited> {

        Filtered(final Unfiltered unfiltered, final List<UnboundVariable> vars) {
            super(unfiltered.conjunction(), vars, null, null, null);
        }

        @Override
        public GraqlMatch.Sorted sort(final Sorting sorting) {
            return new GraqlMatch.Sorted(this, sorting);
        }

        @Override
        public GraqlMatch.Offsetted offset(final long offset) {
            return new GraqlMatch.Offsetted(this, offset);
        }

        @Override
        public GraqlMatch.Limited limit(final long limit) {
            return new GraqlMatch.Limited(this, limit);
        }
    }

    public static class Sorted extends GraqlMatch {

        Sorted(final GraqlMatch match, final Sortable.Sorting sorting) {
            super(match.conjunction, match.filter, sorting, match.offset, match.limit);
        }

        public GraqlMatch.Offsetted offset(final long offset) {
            return new GraqlMatch.Offsetted(this, offset);
        }

        public GraqlMatch.Limited limit(final long limit) {
            return new GraqlMatch.Limited(this, limit);
        }
    }

    public static class Offsetted extends GraqlMatch {

        Offsetted(final GraqlMatch match, final long offset) {
            super(match.conjunction, match.filter, match.sorting, offset, match.limit);
        }

        public GraqlMatch.Limited limit(final long limit) {
            return new GraqlMatch.Limited(this, limit);
        }
    }

    public static class Limited extends GraqlMatch {

        Limited(final GraqlMatch match, final long limit) {
            super(match.conjunction, match.filter, match.sorting, match.offset, limit);
        }
    }

    public static class Aggregate extends GraqlQuery {

        private final GraqlMatch query;
        private final GraqlToken.Aggregate.Method method;
        private final UnboundVariable var;
        private final int hash;

        Aggregate(final GraqlMatch query, final GraqlToken.Aggregate.Method method, final UnboundVariable var) {
            if (query == null) throw new NullPointerException("GetQuery is null");
            if (method == null) throw new NullPointerException("Method is null");


            if (var == null && !method.equals(GraqlToken.Aggregate.Method.COUNT)) {
                throw new NullPointerException("Variable is null");
            } else if (var != null && method.equals(GraqlToken.Aggregate.Method.COUNT)) {
                throw new IllegalArgumentException("Aggregate COUNT does not accept a Variable");
            } else if (var != null && !query.filter().contains(var)) {
                throw GraqlException.of(VARIABLE_OUT_OF_SCOPE.message(var.toString()));
            }

            this.query = query;
            this.method = method;
            this.var = var;
            this.hash = Objects.hash(query, method, var);
        }

        public GraqlMatch query() {
            return query;
        }

        public GraqlToken.Aggregate.Method method() {
            return method;
        }

        public UnboundVariable var() {
            return var;
        }

        @Override
        public final String toString() {
            final StringBuilder query = new StringBuilder();

            if (query().filter.isEmpty() && query().conjunction().patterns().size() > 1) {
                query.append(query()).append(NEW_LINE);
            } else query.append(query()).append(SPACE);

            query.append(method);
            if (var != null) query.append(SPACE).append(var);
            query.append(SEMICOLON);

            return query.toString();
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final Aggregate that = (Aggregate) o;

            return (this.query.equals(that.query) &&
                    this.method.equals(that.method) &&
                    Objects.equals(this.var, that.var));
        }

        @Override
        public int hashCode() {
            return hash;
        }

    }

    public static class Group extends GraqlQuery implements Aggregatable<Group.Aggregate> {

        private final GraqlMatch query;
        private final UnboundVariable var;
        private final int hash;

        Group(final GraqlMatch query, final UnboundVariable var) {
            if (query == null) throw new NullPointerException("GetQuery is null");
            if (var == null) throw new NullPointerException("Variable is null");
            else if (!query.filter().contains(var))
                throw GraqlException.of(VARIABLE_OUT_OF_SCOPE.message(var.toString()));

            this.query = query;
            this.var = var;
            this.hash = Objects.hash(query, var);
        }

        public GraqlMatch query() {
            return query;
        }

        public UnboundVariable var() {
            return var;
        }

        @Override
        public Aggregate aggregate(final GraqlToken.Aggregate.Method method, final UnboundVariable var) {
            return new Aggregate(this, method, var);
        }

        @Override
        public String toString() {
            final StringBuilder query = new StringBuilder();

            if (query().filter.isEmpty() && query().conjunction().patterns().size() > 1) {
                query.append(query()).append(NEW_LINE);
            } else query.append(query()).append(SPACE);

            query.append(GROUP).append(SPACE).append(var).append(SEMICOLON);
            return query.toString();
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final Group that = (Group) o;

            return (this.query.equals(that.query) &&
                    this.var.equals(that.var));
        }

        @Override
        public int hashCode() {
            return hash;
        }

        public static class Aggregate extends GraqlQuery {

            private final GraqlMatch.Group group;
            private final GraqlToken.Aggregate.Method method;
            private final UnboundVariable var;
            private final int hash;

            Aggregate(final GraqlMatch.Group group, final GraqlToken.Aggregate.Method method, final UnboundVariable var) {
                if (group == null) throw new NullPointerException("GraqlGet.Group is null");
                if (method == null) throw new NullPointerException("Method is null");
                if (var == null && !method.equals(GraqlToken.Aggregate.Method.COUNT)) {
                    throw new NullPointerException("Variable is null");
                } else if (var != null && method.equals(GraqlToken.Aggregate.Method.COUNT)) {
                    throw new IllegalArgumentException("Aggregate COUNT does not accept a Variable");
                } else if (var != null && !group.query().filter().contains(var)) {
                    throw GraqlException.of(VARIABLE_OUT_OF_SCOPE.message(var.toString()));
                }

                this.group = group;
                this.method = method;
                this.var = var;
                this.hash = Objects.hash(group, method, var);
            }

            public GraqlMatch.Group group() {
                return group;
            }

            public GraqlToken.Aggregate.Method method() {
                return method;
            }

            public UnboundVariable var() {
                return var;
            }

            @Override
            public final String toString() {
                final StringBuilder query = new StringBuilder();

                if (group().query().filter.isEmpty() && group().query().conjunction().patterns().size() > 1) {
                    query.append(group().query()).append(NEW_LINE);
                } else query.append(group().query()).append(SPACE);

                query.append(GROUP).append(SPACE).append(group().var()).append(SEMICOLON).append(SPACE).append(method);

                if (var != null) query.append(SPACE).append(var);
                query.append(SEMICOLON);

                return query.toString();
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                final Aggregate that = (Aggregate) o;

                return (this.group.equals(that.group) &&
                        this.method.equals(that.method) &&
                        Objects.equals(this.var, that.var));
            }

            @Override
            public int hashCode() {
                return hash;
            }
        }
    }
}
