SMALL_WORDS = {"a", "an", "and", "as", "at", "but", "by", "for",
               "in", "of", "on", "or", "the", "to", "up"}


def display_name(directory: str) -> str:
    return directory.replace("-", " ")


def slug(directory: str) -> str:
    return directory.lower()


def directory_is_valid(directory: str) -> bool:
    if "_" in directory or " " in directory:
        return False
    parts = directory.split("-")
    for index, part in enumerate(parts):
        if not part:
            return False
        if index > 0 and part.lower() in SMALL_WORDS:
            if part != part.lower():
                return False
        elif not part[0].isupper():
            return False
    return True
